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

		String currentSchema = metadataRepository.currentSchema();
		for (String requesteTable : requestTableSet) {
			// ダブルクォーテーションを除外してドットで分割
			String[] parts = requesteTable.replace("\"", "").split("\\.");
			String schema = currentSchema;
			if (2 <= parts.length) {
				schema = parts[parts.length - 2];
			}

			String table = parts[parts.length - 1];
			TableMetadata metadata = findTable(schema, table);

			if (metadata != null) {
				String lowerRequesteTable = StringUtils.toRootLowerCase(requesteTable);
				String lowerSchemaTable = StringUtils.toRootLowerCase(schema + "." + table);
				String lowerTable = StringUtils.toRootLowerCase(table);

				resultMap.put(lowerRequesteTable, metadata);
				resultMap.put(lowerSchemaTable, metadata);
				resultMap.putIfAbsent(lowerTable, metadata);
			}
		}
		return resultMap;
	}

	/**
	 * テーブル定義を取得
	 */
	public TableMetadata findTable(String schema, String table) {
		List<RawColumn> rawColumnList = metadataRepository.selectRawColumnList(schema, table);
		if (CollectionUtils.isEmpty(rawColumnList)) {
			return null;
		}

		Set<String> pkColumnSet = metadataRepository.selectPkColumnSet(schema, table);
		List<UniqueConstraintColumn> uniqueColumnList = metadataRepository.selectUniqueColumnList(schema, table);
		
		Set<String> uniqueColumnSingleSet = makeUniqueColumnSingleSet(uniqueColumnList, pkColumnSet);
		List<ColumnMetadata> columnMetadataList = makeColumnMetadataList(rawColumnList, pkColumnSet,
				uniqueColumnSingleSet);
		TableMetadata TableMetadata = new TableMetadata(schema, table, columnMetadataList, pkColumnSet,
				uniqueColumnSingleSet);
		return TableMetadata;
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
					uniqueColumnSingleSet.contains(key));
			columnMetadataList.add(columnMetadata);
		}
		return columnMetadataList;
	}
}
