package erd.component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

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
		lines.add("    direction TB");

		// 同じTableMetadataが複数キーで登録されているため、qualifiedNameで重複排除。
		Map<String, TableMetadata> uniqueTables = new LinkedHashMap<>();
		for (TableMetadata tableMetadata : tableMetadataMap.values()) {
			uniqueTables.putIfAbsent(
					tableMetadata.qualifiedName().toLowerCase(Locale.ROOT),
					tableMetadata);
		}

		// 1対多の「1」側を先に定義する。
		// Mermaidの自動レイアウトで親テーブルが上に配置されやすくなる。
		List<TableMetadata> orderedTables =
				orderTablesParentFirst(analysis, tableMetadataMap, uniqueTables);

		for (TableMetadata table : orderedTables) {
			String entity =
					entityNameMap.get(table.qualifiedName().toLowerCase(Locale.ROOT));

			String displayName =
					table.qualifiedName().toLowerCase(Locale.ROOT);

			if (StringUtils.isNotBlank(table.comment())) {
				displayName += " " + table.comment().trim();
			}

			lines.add("");
			lines.add(
					"    " + entity
							+ "[\"" + escapeComment(displayName) + "\"] {");

			for (ColumnMetadata column : table.columnMetadataList()) {
				if (!shouldIncludeColumn(analysis, table, column)) {
					continue;
				}

				String type = sanitizeType(column.dataType());
				String columnName = sanitizeAttribute(column.name());

				String key = StringUtils.EMPTY;
				if (column.primaryKey()) {
					key = " PK";
				} else if (column.unique()) {
					key = " UK";
				}

				String note = buildColumnComment(column);

				lines.add(
						"        "
								+ type
								+ " "
								+ columnName
								+ key
								+ " \""
								+ escapeComment(note)
								+ "\"");
			}

			lines.add("    }");
		}

		for (JoinRelation relation : analysis.relations()) {
			TableRef leftRef =
					analysis.qualifierToTable()
							.get(relation.leftQualifier().toLowerCase(Locale.ROOT));

			TableRef rightRef =
					analysis.qualifierToTable()
							.get(relation.rightQualifier().toLowerCase(Locale.ROOT));

			if (leftRef == null || rightRef == null) {
				continue;
			}

			TableMetadata left =
					findMetadata(leftRef, tableMetadataMap);

			TableMetadata right =
					findMetadata(rightRef, tableMetadataMap);

			if (left == null || right == null) {
				continue;
			}

			ColumnMetadata leftColumn =
					left.column(relation.leftColumn());

			ColumnMetadata rightColumn =
					right.column(relation.rightColumn());

			boolean rightIsParent =
					leftColumn != null
							&& rightColumn != null
							&& !leftColumn.unique()
							&& rightColumn.unique();

			TableMetadata first =
					rightIsParent ? right : left;

			TableMetadata second =
					rightIsParent ? left : right;

			String firstColumn =
					rightIsParent
							? relation.rightColumn()
							: relation.leftColumn();

			String secondColumn =
					rightIsParent
							? relation.leftColumn()
							: relation.rightColumn();

			String firstEntity =
					entityNameMap.get(
							first.qualifiedName().toLowerCase(Locale.ROOT));

			String secondEntity =
					entityNameMap.get(
							second.qualifiedName().toLowerCase(Locale.ROOT));

			String connector =
					inferConnector(
							first,
							firstColumn,
							second,
							secondColumn);

			lines.add(
					"    "
							+ firstEntity
							+ " "
							+ connector
							+ " "
							+ secondEntity
							+ " : \""
							+ escapeComment(relation.expression())
							+ "\"");
		}

		if (CollectionUtils.isEmpty(analysis.relations())
				&& 1 < tableMetadataMap.size()) {
			lines.add(
					"    %% JOIN関係を抽出できなかったため、テーブル定義のみ表示");
		}

		return "```mermaid\n"
				+ String.join("\n", lines)
				+ "\n```\n";
	}

	private List<TableMetadata> orderTablesParentFirst(
			SqlAnalysis analysis,
			Map<String, TableMetadata> tableMetadataMap,
			Map<String, TableMetadata> uniqueTables) {

		Map<String, Set<String>> childrenByParent =
				new LinkedHashMap<>();

		Map<String, Integer> indegree =
				new LinkedHashMap<>();

		for (String key : uniqueTables.keySet()) {
			childrenByParent.put(
					key,
					new LinkedHashSet<>());

			indegree.put(key, 0);
		}

		for (JoinRelation relation : analysis.relations()) {
			TableRef leftRef =
					analysis.qualifierToTable()
							.get(relation.leftQualifier().toLowerCase(Locale.ROOT));

			TableRef rightRef =
					analysis.qualifierToTable()
							.get(relation.rightQualifier().toLowerCase(Locale.ROOT));

			if (leftRef == null || rightRef == null) {
				continue;
			}

			TableMetadata left =
					findMetadata(leftRef, tableMetadataMap);

			TableMetadata right =
					findMetadata(rightRef, tableMetadataMap);

			if (left == null || right == null) {
				continue;
			}

			ColumnMetadata leftColumn =
					left.column(relation.leftColumn());

			ColumnMetadata rightColumn =
					right.column(relation.rightColumn());

			if (leftColumn == null
					|| rightColumn == null
					|| leftColumn.unique() == rightColumn.unique()) {
				continue;
			}

			TableMetadata parent =
					leftColumn.unique() ? left : right;

			TableMetadata child =
					leftColumn.unique() ? right : left;

			String parentKey =
					parent.qualifiedName().toLowerCase(Locale.ROOT);

			String childKey =
					child.qualifiedName().toLowerCase(Locale.ROOT);

			if (parentKey.equals(childKey)
					|| !childrenByParent.containsKey(parentKey)
					|| !childrenByParent.containsKey(childKey)) {
				continue;
			}

			if (childrenByParent.get(parentKey).add(childKey)) {
				indegree.put(
						childKey,
						indegree.get(childKey) + 1);
			}
		}

		List<TableMetadata> result =
				new ArrayList<>();

		Set<String> emitted =
				new LinkedHashSet<>();

		while (result.size() < uniqueTables.size()) {
			boolean progressed = false;

			for (String key : uniqueTables.keySet()) {
				if (emitted.contains(key)
						|| indegree.get(key) != 0) {
					continue;
				}

				emitted.add(key);
				result.add(uniqueTables.get(key));

				for (String child : childrenByParent.get(key)) {
					indegree.put(
							child,
							indegree.get(child) - 1);
				}

				progressed = true;
			}

			if (!progressed) {
				// 循環参照がある場合は、残りを元の順序で追加する。
				for (String key : uniqueTables.keySet()) {
					if (emitted.add(key)) {
						result.add(uniqueTables.get(key));
					}
				}
			}
		}

		return result;
	}

	private boolean shouldIncludeColumn(
			SqlAnalysis analysis,
			TableMetadata table,
			ColumnMetadata column) {

		if (column.primaryKey()) {
			return true;
		}

		String columnName =
				column.name().toLowerCase(Locale.ROOT);

		if (analysis.unqualifiedReferencedColumns()
				.contains(columnName)) {
			return true;
		}

		for (TableRef ref : analysis.qualifierToTable().values()) {
			Set<String> referencedColumns =
					analysis.referencedColumnsByTable()
							.get(ref.key());

			if (referencedColumns == null
					|| !referencedColumns.contains(columnName)) {
				continue;
			}

			if (matches(table, ref)) {
				return true;
			}
		}

		return false;
	}

	private boolean matches(
			TableMetadata table,
			TableRef ref) {

		if (!table.name().equalsIgnoreCase(ref.name())) {
			return false;
		}

		return ref.schema() == null
				|| table.schema() == null
				|| table.schema().equalsIgnoreCase(ref.schema());
	}

	private String inferConnector(
			TableMetadata left,
			String leftColumn,
			TableMetadata right,
			String rightColumn) {

		ColumnMetadata leftMeta =
				left.column(leftColumn);

		ColumnMetadata rightMeta =
				right.column(rightColumn);

		if (leftMeta == null || rightMeta == null) {
			return "}o--o{";
		}

		boolean leftUnique =
				leftMeta.unique();

		boolean rightUnique =
				rightMeta.unique();

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

	private Map<String, String> buildEntityNames(
			Map<String, TableMetadata> tableMetadataMap) {

		Map<String, String> result =
				new HashMap<>();

		if (CollectionUtils.isEmpty(tableMetadataMap)) {
			return result;
		}

		Map<String, Integer> used =
				new HashMap<>();

		for (TableMetadata tableMetadata : tableMetadataMap.values()) {
			String key =
					tableMetadata.qualifiedName()
							.toLowerCase(Locale.ROOT);

			if (result.containsKey(key)) {
				continue;
			}

			String base =
					sanitizeEntity(key);

			int n =
					used.merge(base, 1, Integer::sum);

			String name =
					n == 1
							? base
							: base + "_" + n;

			result.put(key, name);
		}

		return result;
	}

	private TableMetadata findMetadata(
			TableRef ref,
			Map<String, TableMetadata> metadata) {

		TableMetadata table =
				metadata.get(
						ref.qualifiedName().toLowerCase(Locale.ROOT));

		if (table == null && ref.schema() != null) {
			table =
					metadata.get(
							(ref.schema() + "." + ref.name())
									.toLowerCase(Locale.ROOT));
		}

		if (table == null && ref.schema() == null) {
			table =
					metadata.get(
							ref.name().toLowerCase(Locale.ROOT));
		}

		return table;
	}

	private String sanitizeEntity(String value) {
		if (StringUtils.isBlank(value)) {
			return "table";
		}

		String sanitizedValue =
				value.replaceAll(
						"[^A-Za-z0-9_]",
						"_");

		if (StringUtils.isBlank(sanitizedValue)) {
			sanitizedValue = "table";
		}

		if (Character.isDigit(sanitizedValue.charAt(0))) {
			sanitizedValue =
					"T_" + sanitizedValue;
		}

		return sanitizedValue.toLowerCase(Locale.ROOT);
	}

	private String sanitizeAttribute(String value) {
		if (StringUtils.isBlank(value)) {
			return "column";
		}

		String sanitizedValue =
				value.replaceAll(
						"[^A-Za-z0-9_]",
						"_");

		if (StringUtils.isBlank(sanitizedValue)) {
			return "column";
		}

		if (Character.isDigit(sanitizedValue.charAt(0))) {
			return "c_" + sanitizedValue;
		}

		return sanitizedValue;
	}

	/**
	 * PostgreSQLのデータ型をMermaid ER Diagramで表示できる形式へ整形する。
	 *
	 * 例:
	 *   numeric(12,2)       -> numeric(12,2)
	 *   varchar(20)         -> varchar(20)
	 *   character varying(20) -> varchar(20)
	 *   timestamp without time zone -> timestamp
	 *   timestamp with time zone    -> timestamptz
	 */
	private String sanitizeType(String value) {
		if (StringUtils.isBlank(value)) {
			return "unknown";
		}

		String sanitizedValue = value
				.toLowerCase(Locale.ROOT)
				.replace(
						"timestamp without time zone",
						"timestamp")
				.replace(
						"timestamp with time zone",
						"timestamptz")
				.replace(
						"character varying",
						"varchar")
				.replace(
						"double precision",
						"double")
				// () と , を許可する。
				// numeric(12,2) や varchar(20) をそのまま表示するため。
				.replaceAll(
						"[^a-z0-9_\\[\\](),]",
						"_");

		if (StringUtils.isBlank(sanitizedValue)) {
			return "unknown";
		}

		return sanitizedValue;
	}

	private String buildColumnComment(ColumnMetadata column) {
		if (StringUtils.isBlank(column.comment())) {
			return StringUtils.EMPTY;
		}

		return column.comment().trim();
	}

	private String escapeComment(String value) {
		if (StringUtils.isBlank(value)) {
			return StringUtils.EMPTY;
		}

		String sanitizedValue = value
				.replace("\\", "\\\\")
				.replace("\"", "'")
				.replace("\r", " ")
				.replace("\n", " ");

		if (StringUtils.isBlank(sanitizedValue)) {
			return StringUtils.EMPTY;
		}

		return sanitizedValue;
	}
}
