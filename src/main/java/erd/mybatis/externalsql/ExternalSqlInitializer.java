package erd.mybatis.externalsql;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.ibatis.session.SqlSessionFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.context.ApplicationContext;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.stereotype.Component;

import erd.mybatis.model.ExternalSql;
import erd.mybatis.model.ExternalSqlValidationError;
import erd.mybatis.model.IndexedSqlResource;

/**
 * 外部SQLを起動時に一括走査・検証し、Providerが利用するキャッシュを構築する。
 * Resource走査は未使用SQL検出と必須SQL検証で共有し、一度だけ実行する。
 */
@Component
public final class ExternalSqlInitializer implements SmartInitializingSingleton {

	private static final Logger log = LoggerFactory.getLogger(ExternalSqlInitializer.class);

	private final List<SqlSessionFactory> sqlSessionFactories;
	private final ResourcePatternResolver resourcePatternResolver;

	/**
	 * External SQLの初期化処理を生成します。
	 *
	 * @param sqlSessionFactories MyBatisのSQLセッションファクトリ一覧
	 * @param applicationContext  SQLリソースの検索に使用するSpringのアプリケーションコンテキスト
	 */
	public ExternalSqlInitializer(List<SqlSessionFactory> sqlSessionFactories, ApplicationContext applicationContext) {
		this.sqlSessionFactories = sqlSessionFactories;
		this.resourcePatternResolver = applicationContext;
	}

	/**
	 * SpringのSingleton Bean生成完了後にExternal SQLを初期化します。
	 * <p>
	 * SQLリソースを一度だけ走査してインデックスを作成し、
	 * {@link ExternalSqlProvider} を使用するMapperメソッドに対応するSQLについて、
	 * 存在確認、重複確認、空SQL確認、メソッドのオーバーロード確認を行います。
	 * </p>
	 * <p>
	 * また、Mapperから参照されていないSQLをINFOログに出力します。
	 * すべての検証に成功した場合のみ、検証済みSQLを
	 * {@link ExternalSqlCache} に登録します。
	 * </p>
	 *
	 * @throws IllegalStateException External SQLの検証でエラーが検出された場合
	 */
	@Override
	public void afterSingletonsInstantiated() {
		ExternalSqlResourceIndex index = ExternalSqlResourceIndex.scan(resourcePatternResolver);
		Set<Class<?>> mapperTypes = findMapperTypes();
		List<ExternalSqlValidationError> errors = new ArrayList<>();
		Set<String> requiredPaths = new HashSet<>();
		Map<String, ExternalSql> validatedSql = new LinkedHashMap<>();

		for (Class<?> mapperType : mapperTypes) {
			validateMapper(mapperType, index, requiredPaths, validatedSql, errors);
		}

		logUnusedSql(index, requiredPaths);
		if (!errors.isEmpty()) {
			throwValidationException(errors);
		}

		ExternalSqlCache.initialize(validatedSql);
		log.info("External SQL initialization completed. count={}", validatedSql.size());
	}

	/**
	 * MyBatisに登録されているすべてのMapper型を取得します。
	 * <p>
	 * 複数の{@link SqlSessionFactory}が存在する場合は、
	 * 各SqlSessionFactoryに登録されているMapperをまとめて取得します。
	 * </p>
	 *
	 * @return MyBatisに登録されているMapper型の集合
	 */
	private Set<Class<?>> findMapperTypes() {
		return sqlSessionFactories.stream()
				.flatMap(factory -> factory.getConfiguration().getMapperRegistry().getMappers().stream())
				.collect(Collectors.toSet());
	}

	/**
	 * Mapperに定義されたExternal SQL対象メソッドを検証します。
	 * <p>
	 * {@link ExternalSqlProvider} を使用しているメソッドについて、
	 * 対応するSQLファイルのパスを生成し、必須SQLとして登録します。
	 * また、同名のオーバーロードメソッドが存在する場合は検証エラーとして記録します。
	 * </p>
	 *
	 * @param mapperType   検証対象のMapper型
	 * @param index        起動時に構築したSQLリソースのインデックス
	 * @param requiredPaths External SQL対象メソッドから参照されるSQLパスの集合
	 * @param validatedSql 検証済みSQLを格納するMap
	 * @param errors       検出した検証エラーの格納先
	 */
	private void validateMapper(Class<?> mapperType, ExternalSqlResourceIndex index, Set<String> requiredPaths,
			Map<String, ExternalSql> validatedSql, List<ExternalSqlValidationError> errors) {
		Method[] methods = mapperType.getMethods();
		Map<String, List<Method>> methodsByName = Arrays.stream(methods)
				.collect(Collectors.groupingBy(Method::getName));

		for (Method method : methods) {
			if (!ExternalSqlMethodDetector.isExternalSqlMethod(method)) {
				continue;
			}

			String path = ExternalSqlPath.of(mapperType, method);
			requiredPaths.add(path);
			List<Method> sameNameMethods = methodsByName.get(method.getName());
			if (sameNameMethods.size() > 1) {
				errors.add(new ExternalSqlValidationError(path,
						"Overloaded mapper method is not allowed: " + mapperType.getName() + "#" + method.getName()));
				continue;
			}

			validateSqlResource(path, index, validatedSql, errors);
		}
	}

	/**
	 * 指定されたパスに対応するSQLリソースを検証します。
	 * <p>
	 * SQLファイルが存在しない場合、同一パスのSQLファイルが複数存在する場合、
	 * またはSQL本文が空文字もしくは空白文字のみの場合は、
	 * 検証エラーとして記録します。
	 * </p>
	 * <p>
	 * 検証に成功したSQLは、実行時キャッシュへ登録するため
	 * {@code validatedSql} に追加します。
	 * </p>
	 *
	 * @param path         検証対象のSQLリソースパス
	 * @param index        起動時に構築したSQLリソースのインデックス
	 * @param validatedSql 検証済みSQLを格納するMap
	 * @param errors       検出した検証エラーの格納先
	 */
	private void validateSqlResource(String path, ExternalSqlResourceIndex index, Map<String, ExternalSql> validatedSql,
			List<ExternalSqlValidationError> errors) {
		List<IndexedSqlResource> resources = index.find(path);
		if (resources.isEmpty()) {
			errors.add(new ExternalSqlValidationError(path, "SQL file does not exist."));
			return;
		}
		if (resources.size() > 1) {
			String locations = resources.stream()
					.map(resource -> resource.resource().getDescription())
					.collect(Collectors.joining(", "));
			errors.add(new ExternalSqlValidationError(path, "Multiple SQL files exist: " + locations));
			return;
		}

		ExternalSql sql = resources.getFirst().externalSql();
		if (sql.sql().isBlank()) {
			errors.add(new ExternalSqlValidationError(path, "SQL file is empty or blank."));
			return;
		}
		validatedSql.put(path, sql);
	}

	/**
	 * Mapperから参照されていないExternal SQLファイルをINFOログに出力します。
	 * <p>
	 * 起動時に走査したすべてのSQLリソースと、
	 * {@link ExternalSqlProvider} 対象メソッドが必要とするSQLパスを比較し、
	 * 使用されていないSQLファイルを検出します。
	 * </p>
	 *
	 * @param index         起動時に構築したSQLリソースのインデックス
	 * @param requiredPaths External SQL対象メソッドから参照されるSQLパスの集合
	 */
	private void logUnusedSql(ExternalSqlResourceIndex index, Set<String> requiredPaths) {
		index.resourcesByPath().keySet().stream()
				.filter(path -> !requiredPaths.contains(path))
				.sorted()
				.forEach(path -> log.info("Unused external SQL file: {}", path));
	}

	/**
	 * External SQLの検証エラーを一覧化して例外を送出します。
	 * <p>
	 * 検出されたすべてのエラーについてSQLパスとエラー内容を
	 * 1つのメッセージにまとめ、{@link IllegalStateException}として送出します。
	 * </p>
	 *
	 * @param errors 検出されたExternal SQLの検証エラー一覧
	 * @throws IllegalStateException 検証エラー一覧を含む例外
	 */
	private void throwValidationException(List<ExternalSqlValidationError> errors) {
		String message = errors.stream()
				.map(error -> " - " + error.path() + " : " + error.message())
				.collect(Collectors.joining(System.lineSeparator(),
						"External SQL validation failed:" + System.lineSeparator(), ""));
		throw new IllegalStateException(message);
	}
}
