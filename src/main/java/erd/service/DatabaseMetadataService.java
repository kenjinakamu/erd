package erd.service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;

import erd.repository.DatabaseMetadataRepository;
import erd.repository.model.RawColumn;
import erd.repository.model.UniqueConstraintColumn;
import erd.service.model.ColumnMetadata;
import erd.service.model.TableMetadata;

@Service
public class DatabaseMetadataService {

	private final DatabaseMetadataRepository metadataRepository;

	public DatabaseMetadataService(DatabaseMetadataRepository metadataRepository) {
		this.metadataRepository = metadataRepository;
	}

	/**
	 * テーブルを読み込み
	 */
	public Map<String, TableMetadata> loadTables(Set<String> requestTableSet) {
		Map<String, TableMetadata> resultMap = new LinkedHashMap<>();
		if (CollectionUtils.isEmpty(requestTableSet)) {
			return resultMap;
		}

		List<String> searchPathSchemas = metadataRepository.searchPathSchemas();
		for (String requestedTable : requestTableSet) {
			QualifiedTableName requested = parseTableName(requestedTable);
			TableMetadata metadata;

			if (requested.schema() != null) {
				// SQLでスキーマが明示されている場合は、その名前空間を必ず使用する。
				metadata = findTable(requested.schema(), requested.table());
			} else {
				// 未修飾テーブルはPostgreSQL本体と同じくsearch_path順で解決する。
				metadata = findTableOnSearchPath(searchPathSchemas, requested.table());
			}

			if (metadata == null) {
				continue;
			}

			String requestKey = StringUtils.toRootLowerCase(normalizeIdentifierPath(requestedTable));
			String qualifiedKey = StringUtils.toRootLowerCase(metadata.qualifiedName());
			resultMap.put(requestKey, metadata);
			resultMap.put(qualifiedKey, metadata);

			// 未修飾名は「そのSQL内で未修飾で参照された場合」だけ登録する。
			// schema1.foo と schema2.foo が混在しても foo キーで互いを上書きしない。
			if (requested.schema() == null) {
				resultMap.put(StringUtils.toRootLowerCase(requested.table()), metadata);
			}
		}
		return resultMap;
	}

	private TableMetadata findTableOnSearchPath(List<String> searchPathSchemas, String table) {
		if (!CollectionUtils.isEmpty(searchPathSchemas)) {
			for (String schema : searchPathSchemas) {
				TableMetadata metadata = findTable(schema, table);
				if (metadata != null) {
					return metadata;
				}
			}
		}
		return null;
	}

	private QualifiedTableName parseTableName(String value) {
		List<IdentifierPart> parts = splitIdentifierPath(value);
		if (parts.isEmpty()) {
			throw new IllegalArgumentException("テーブル名が空です。");
		}
		String table = parts.getLast().databaseName();
		String schema = parts.size() >= 2 ? parts.get(parts.size() - 2).databaseName() : null;
		return new QualifiedTableName(schema, table);
	}

	/**
	 * PostgreSQLの識別子規則に従って schema.table を分解する。
	 * 未引用識別子は小文字化し、二重引用符付き識別子は大小文字を保持する。
	 */
	private List<IdentifierPart> splitIdentifierPath(String value) {
		List<IdentifierPart> parts = new ArrayList<>();
		StringBuilder part = new StringBuilder();
		boolean quoted = false;
		boolean partQuoted = false;
		for (int i = 0; i < value.length(); i++) {
			char ch = value.charAt(i);
			if (ch == '"') {
				if (quoted && i + 1 < value.length() && value.charAt(i + 1) == '"') {
					part.append('"');
					i++;
				} else {
					quoted = !quoted;
					partQuoted = true;
				}
			} else if (ch == '.' && !quoted) {
				addIdentifierPart(parts, part, partQuoted);
				part.setLength(0);
				partQuoted = false;
			} else {
				part.append(ch);
			}
		}
		addIdentifierPart(parts, part, partQuoted);
		return parts;
	}

	private void addIdentifierPart(List<IdentifierPart> parts, StringBuilder value, boolean quoted) {
		String text = value.toString().trim();
		if (text.isEmpty()) {
			return;
		}
		parts.add(new IdentifierPart(text, quoted));
	}

	private String normalizeIdentifierPath(String value) {
		return splitIdentifierPath(value).stream()
				.map(IdentifierPart::databaseName)
				.reduce((left, right) -> left + "." + right)
				.orElse("");
	}

	private record IdentifierPart(String value, boolean quoted) {
		String databaseName() {
			return quoted ? value : StringUtils.toRootLowerCase(value);
		}
	}

	private record QualifiedTableName(String schema, String table) {
	}

	/**
	 * テーブル定義を取得
	 */
	public TableMetadata findTable(String schema, String table) {
		List<RawColumn> rawColumnList = metadataRepository.selectRawColumnList(schema, table);
		if (CollectionUtils.isEmpty(rawColumnList)) {
			return null;
		}

		String tableComment = metadataRepository.selectTableComment(schema, table);
		Set<String> pkColumnSet = metadataRepository.selectPkColumnSet(schema, table);
		List<UniqueConstraintColumn> uniqueColumnList = metadataRepository.selectUniqueColumnList(schema, table);

		Set<String> uniqueColumnSingleSet = makeUniqueColumnSingleSet(uniqueColumnList, pkColumnSet);
		List<ColumnMetadata> columnMetadataList = makeColumnMetadataList(rawColumnList, pkColumnSet,
				uniqueColumnSingleSet);
        return new TableMetadata(schema, table, tableComment, columnMetadataList, pkColumnSet,
                uniqueColumnSingleSet);
	}

	/**
	 * 単一構成のユニークカラムのセットを作成
	 */
	private Set<String> makeUniqueColumnSingleSet(List<UniqueConstraintColumn> uniqueColumnList,
												  Set<String> pkColumnSet) {
		Set<String> uniqueColumnSingleSet = new HashSet<>();
		for (UniqueConstraintColumn uniqueColumn : uniqueColumnList) {
			if (uniqueColumn.columnCount() == 1) {
				String lowercolumnName = StringUtils.toRootLowerCase(uniqueColumn.columnName());
				uniqueColumnSingleSet.add(lowercolumnName);
			}
		}
		uniqueColumnSingleSet.addAll(pkColumnSet);
		return uniqueColumnSingleSet;
	}

	/**
	 * カラムのメタ情報のリストを作成
	 */
	private List<ColumnMetadata> makeColumnMetadataList(List<RawColumn> rawColumnList, Set<String> pkColumnSet,
														Set<String> uniqueColumnSingleSet) {
		List<ColumnMetadata> columnMetadataList = new ArrayList<>();
		for (RawColumn rawColumn : rawColumnList) {
			String key = StringUtils.toRootLowerCase(rawColumn.name());
			ColumnMetadata columnMetadata = new ColumnMetadata(
					rawColumn.name(),
					rawColumn.type(),
					rawColumn.nullable(),
					pkColumnSet.contains(key),
					uniqueColumnSingleSet.contains(key),
					rawColumn.comment());
			columnMetadataList.add(columnMetadata);
		}
		return columnMetadataList;
	}
}
