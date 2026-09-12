package erd.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;

import erd.component.MermaidGenerator;
import erd.component.SqlAnalyzer;
import erd.controller.dto.ErdResponse;
import erd.service.model.SqlAnalysis;
import erd.service.model.TableMetadata;

/**
 * ER図サービス
 */
@Service
public class ErdService {

	private final SqlAnalyzer sqlAnalyzer;
	private final DatabaseMetadataService metadataService;
	private final MermaidGenerator mermaidGenerator;

	public ErdService(SqlAnalyzer sqlAnalyzer,
					  DatabaseMetadataService metadataService,
					  MermaidGenerator mermaidGenerator) {
		this.sqlAnalyzer = sqlAnalyzer;
		this.metadataService = metadataService;
		this.mermaidGenerator = mermaidGenerator;
	}

	/**
	 * SQLからER図を生成
	 */
	public ErdResponse generate(String sql) {
		SqlAnalysis analysis = sqlAnalyzer.analyze(sql);
		Set<String> tableNameSet = analysis.tableNames();
		Map<String, TableMetadata> metadata = metadataService.loadTables(tableNameSet);

		List<String> warnings = new ArrayList<>(analysis.warnings());
		for (String tableName : tableNameSet) {
			String lowerTableName = StringUtils.toRootLowerCase(tableName);
			if (!metadata.containsKey(lowerTableName)) {
				warnings.add("PostgreSQLからテーブル定義を取得できませんでした: " + tableName +
						"（schema / search_path / 権限を確認してください）");
			}
		}

		if (CollectionUtils.isEmpty(metadata)) {
			throw new IllegalArgumentException("対象テーブルのメタデータを取得できませんでした。解析対象: "
					+ String.join(", ", tableNameSet));
		}

		String mermaid = mermaidGenerator.generate(analysis, metadata);
		List<String> tableNameList = tableNameSet.stream().toList();
		List<String> distinctWarningList = warnings.stream().distinct().toList();
		ErdResponse erdResponse = new ErdResponse(mermaid, tableNameList, distinctWarningList);
		return erdResponse;
	}
}
