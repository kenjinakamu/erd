package erd.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;

import erd.service.model.ColumnMetadata;
import erd.service.model.JoinRelation;
import erd.service.model.SqlAnalysis;
import erd.service.model.TableMetadata;
import erd.service.model.TableRef;

@Component
public class MermaidGenerator {

	/**
	 * ER図を生成
	 */
	public String generate(SqlAnalysis analysis, Map<String, TableMetadata> tableMetadataMap) {
		Map<String, String> entityNameMap = buildEntityNames(tableMetadataMap);
		List<String> lines = new ArrayList<>();
		lines.add("erDiagram");

		for (JoinRelation relation : analysis.relations()) {
			TableRef leftRef = analysis.qualifierToTable().get(relation.leftQualifier().toLowerCase(Locale.ROOT));
			TableRef rightRef = analysis.qualifierToTable().get(relation.rightQualifier().toLowerCase(Locale.ROOT));
			if (leftRef == null || rightRef == null) {
				continue;
			}

			TableMetadata left = findMetadata(leftRef, tableMetadataMap);
			TableMetadata right = findMetadata(rightRef, tableMetadataMap);
			if (left == null || right == null) {
				continue;
			}

			String leftEntity = entityNameMap.get(left.qualifiedName().toLowerCase(Locale.ROOT));
			String rightEntity = entityNameMap.get(right.qualifiedName().toLowerCase(Locale.ROOT));
			String connector = inferConnector(left, relation.leftColumn(), right, relation.rightColumn());
			lines.add("    " + leftEntity + " " + connector + " " + rightEntity +
					" : \"" + escapeComment(relation.expression()) + "\"");
		}

		if (CollectionUtils.isEmpty(analysis.relations()) && 1 < tableMetadataMap.size()) {
			lines.add("    %% JOIN関係を抽出できなかったため、テーブル定義のみ表示");
		}

		// 同じTableMetadataが複数キーで登録されているため、qualifiedNameで重複排除。
		Map<String, TableMetadata> uniqueTables = new LinkedHashMap<>();
		for (TableMetadata tableMetadata : tableMetadataMap.values()) {
			uniqueTables.putIfAbsent(tableMetadata.qualifiedName().toLowerCase(Locale.ROOT), tableMetadata);
		}

		for (TableMetadata table : uniqueTables.values()) {
			String entity = entityNameMap.get(table.qualifiedName().toLowerCase(Locale.ROOT));
			lines.add("");
			lines.add("    " + entity + " {");

			for (ColumnMetadata column : table.columnMetadataList()) {
				String type = sanitizeType(column.dataType());
				String columnName = sanitizeAttribute(column.name());
				String key = StringUtils.EMPTY;
				if (column.primaryKey()) {
					key = " PK";
				} else if (column.unique()) {
					key = " UK";
				}
				String note = "not null";
				if (column.nullable()) {
					note = "nullable";
				}
				lines.add("        " + type + " " + columnName + key + " \"" + note + "\"");
			}
			lines.add("    }");
		}

		return "```mermaid\n" + String.join("\n", lines) + "\n```\n";
	}

	private String inferConnector(TableMetadata left, String leftColumn,
			TableMetadata right, String rightColumn) {
		ColumnMetadata leftMeta = left.column(leftColumn);
		ColumnMetadata rightMeta = right.column(rightColumn);
		if (leftMeta == null || rightMeta == null) {
			return "}o--o{";
		}

		boolean leftUnique = leftMeta.unique();
		boolean rightUnique = rightMeta.unique();

		if (leftUnique && rightUnique) {
			return "||--||";
		}
		if (leftUnique) {
			return "||--o{";
		}
		if (rightUnique) {
			return "}o--||";
		}
		return "}o--o{";
	}

	private Map<String, String> buildEntityNames(Map<String, TableMetadata> tableMetadataMap) {
		Map<String, String> result = new HashMap<>();
		if (CollectionUtils.isEmpty(tableMetadataMap)) {
			return result;
		}

		Map<String, Integer> used = new HashMap<>();
		for (TableMetadata tableMetadata : tableMetadataMap.values()) {
			String key = tableMetadata.qualifiedName().toLowerCase(Locale.ROOT);
			if (result.containsKey(key)) {
				continue;
			}

			String base = sanitizeEntity(tableMetadata.name());
			int n = used.merge(base, 1, Integer::sum);
			String name = StringUtils.EMPTY;
			if (n == 1) {
				name = base;
			} else {
				String tableSchema = tableMetadata.schema();
				String tableName = tableMetadata.name();
				name = tableSchema + "_" + tableName;
			}
			result.put(key, name);
		}
		return result;
	}

	private TableMetadata findMetadata(TableRef ref, Map<String, TableMetadata> metadata) {
		TableMetadata table = metadata.get(ref.qualifiedName().toLowerCase(Locale.ROOT));
		if (table == null) {
			table = metadata.get(ref.name().toLowerCase(Locale.ROOT));
		}
		return table;
	}

	private String sanitizeEntity(String value) {
		if (StringUtils.isBlank(value)) {
			return "TABLE";
		}

		String sanitizedValue = value.replaceAll("[^A-Za-z0-9_]", "_");
		if (StringUtils.isBlank(sanitizedValue)) {
			sanitizedValue = "TABLE";
		}

		if (Character.isDigit(sanitizedValue.charAt(0))) {
			sanitizedValue = "T_" + sanitizedValue;
		}
		return sanitizedValue.toUpperCase(Locale.ROOT);
	}

	private String sanitizeAttribute(String value) {
		if (StringUtils.isBlank(value)) {
			return "column";
		}

		String sanitizedValue = value.replaceAll("[^A-Za-z0-9_]", "_");
		if (StringUtils.isBlank(sanitizedValue)) {
			return "column";
		}

		if (Character.isDigit(sanitizedValue.charAt(0))) {
			return "c_" + sanitizedValue;
		}
		return sanitizedValue;
	}

	private String sanitizeType(String value) {
		if (StringUtils.isBlank(value)) {
			return "unknown";
		}

		String sanitizedValue = value.toLowerCase(Locale.ROOT)
				.replace("timestamp without time zone", "timestamp")
				.replace("timestamp with time zone", "timestamptz")
				.replace("character varying", "varchar")
				.replace("double precision", "double")
				.replaceAll("[^a-z0-9_\\[\\]]", "_");
		if (StringUtils.isBlank(sanitizedValue)) {
			return "unknown";
		}

		return sanitizedValue;
	}

	private String escapeComment(String value) {
		if (StringUtils.isBlank(value)) {
			return StringUtils.EMPTY;
		}

		String sanitizedValue = value.replace("\\", "\\\\").replace("\"", "'");
		if (StringUtils.isBlank(sanitizedValue)) {
			return StringUtils.EMPTY;
		}

		return sanitizedValue;
	}
}
