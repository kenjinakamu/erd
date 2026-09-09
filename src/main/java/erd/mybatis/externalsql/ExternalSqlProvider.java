package erd.mybatis.externalsql;

import org.apache.ibatis.builder.annotation.ProviderContext;

public final class ExternalSqlProvider {

	private ExternalSqlProvider() {
	}

	/**
	 * 対応するSQLファイルをキャッシュから取得
	 * src/main/resources/{パッケージ名}/{クラス名}/{メソッド名}.sql
	 *
	 * @param context MyBatisから渡されるProvider実行時のコンテキスト
	 * @return Mapperメソッドに対応するSQL本文
	 * @throws IllegalStateException 対応するSQLがキャッシュに存在しない場合
	 */
	public static String provideSql(ProviderContext context) {
		String path = ExternalSqlPath.of(context.getMapperType(), context.getMapperMethod());
		return ExternalSqlCache.get(path).sql();
	}
}
