package erd.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import erd.service.model.JoinRelation;
import erd.service.model.SqlAnalysis;
import erd.service.model.TableRef;
import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.util.TablesNamesFinder;

@Component
public class SqlAnalyzer {

	private static final String IDENT = "(?:\\\"[^\\\"]+\\\"|[A-Za-z_][A-Za-z0-9_$]*)";
	private static final String QUALIFIED = IDENT + "(?:\\s*\\.\\s*" + IDENT + ")?";

	private static final Pattern TABLE_PATTERN = Pattern.compile(
			"(?is)\\b(?:from|join)\\s+(" + QUALIFIED + ")" +
					"(?:\\s+(?:as\\s+)?(" + IDENT + "))?");

	private static final Pattern JOIN_BLOCK_PATTERN = Pattern.compile(
			"(?is)\\bjoin\\s+" + QUALIFIED +
					"(?:\\s+(?:as\\s+)?" + IDENT + ")?\\s+on\\s+" +
					"(.+?)(?=\\b(?:left|right|full|inner|cross)?\\s*join\\b|" +
					"\\bwhere\\b|\\bgroup\\s+by\\b|\\bhaving\\b|\\border\\s+by\\b|" +
					"\\blimit\\b|\\boffset\\b|\\bfetch\\b|\\bunion\\b|;|$)");

	private static final Pattern COLUMN_EQUALITY_PATTERN = Pattern.compile(
			"(?is)(" + IDENT + ")\\s*\\.\\s*(" + IDENT + ")\\s*=\\s*" +
					"(" + IDENT + ")\\s*\\.\\s*(" + IDENT + ")");

	private static final Set<String> RESERVED = Set.of(
			"on", "where", "join", "left", "right", "full", "inner", "cross", "outer",
			"group", "order", "having", "limit", "offset", "fetch", "union", "except", "intersect");

	/**
	 * SQLを解析
	 */
	public SqlAnalysis analyze(String sql) {
		validateSelect(sql);

		Set<String> actualTables;
		try {
			actualTables = new LinkedHashSet<>(TablesNamesFinder.findTables(sql));
		} catch (JSQLParserException e) {
			throw new IllegalArgumentException("SQLを解析できません: " + e.getMessage(), e);
		}

		Map<String, TableRef> aliases = extractAliases(sql, actualTables);
		List<JoinRelation> relations = extractJoinRelations(sql, aliases);
		List<String> warnings = new ArrayList<>();

		if (actualTables.isEmpty()) {
			warnings.add("実テーブルを検出できませんでした。");
		}
		if (actualTables.size() > 1 && relations.isEmpty()) {
			warnings.add("JOINの列同士の等価条件（例: a.id = b.a_id）を検出できませんでした。テーブル自体は表示します。");
		}
		if (sql.toLowerCase(Locale.ROOT).contains(" with ")
				|| sql.stripLeading().toLowerCase(Locale.ROOT).startsWith("with ")) {
			warnings.add("WITH句/CTEを含むSQLでは、実テーブルは抽出しますが、CTEをまたぐJOIN関係の推定は限定的です。");
		}
		if (sql.contains("(") && sql.toLowerCase(Locale.ROOT).contains("select")) {
			warnings.add("サブクエリを含む場合、JOIN関係の抽出はトップレベルの一般的なJOIN構文を主対象とします。");
		}

		return new SqlAnalysis(actualTables, aliases, relations, warnings.stream().distinct().toList());
	}

	private void validateSelect(String sql) {
		try {
			Statement statement = CCJSqlParserUtil.parse(sql);
			if (!(statement instanceof Select)) {
				throw new IllegalArgumentException("SELECT文のみ対応しています。");
			}
		} catch (JSQLParserException e) {
			throw new IllegalArgumentException("SQLの構文エラー: " + e.getMessage(), e);
		}
	}

	private Map<String, TableRef> extractAliases(String sql, Set<String> actualTables) {
		Map<String, TableRef> result = new LinkedHashMap<>();
		Matcher matcher = TABLE_PATTERN.matcher(sql);
		while (matcher.find()) {
			String rawTable = cleanIdentifierPath(matcher.group(1));
			String rawAlias = cleanIdentifier(matcher.group(2));
			if (rawAlias != null && RESERVED.contains(rawAlias.toLowerCase(Locale.ROOT))) {
				rawAlias = null;
			}

			String matchedActual = findActualTable(rawTable, actualTables);
			if (matchedActual == null) {
				continue;
			}

			String[] parts = splitQualified(matchedActual);
			String schema = parts.length > 1 ? parts[parts.length - 2] : null;
			String name = parts[parts.length - 1];
			TableRef ref = new TableRef(schema, name, rawAlias, matchedActual);

			result.put(name.toLowerCase(Locale.ROOT), ref);
			result.put(matchedActual.toLowerCase(Locale.ROOT), ref);
			if (rawAlias != null) {
				result.put(rawAlias.toLowerCase(Locale.ROOT), ref);
			}
		}

		// 正規表現で拾えなかったテーブルも最低限登録する
		for (String table : actualTables) {
			String[] parts = splitQualified(table);
			String name = parts[parts.length - 1];
			String schema = parts.length > 1 ? parts[parts.length - 2] : null;
			TableRef ref = new TableRef(schema, name, null, table);
			result.putIfAbsent(name.toLowerCase(Locale.ROOT), ref);
			result.putIfAbsent(table.toLowerCase(Locale.ROOT), ref);
		}
		return result;
	}

	private List<JoinRelation> extractJoinRelations(String sql, Map<String, TableRef> aliases) {
		List<JoinRelation> relations = new ArrayList<>();
		Matcher joinMatcher = JOIN_BLOCK_PATTERN.matcher(sql);
		while (joinMatcher.find()) {
			String onExpression = joinMatcher.group(1);
			Matcher equality = COLUMN_EQUALITY_PATTERN.matcher(onExpression);
			while (equality.find()) {
				String leftQ = cleanIdentifier(equality.group(1));
				String leftC = cleanIdentifier(equality.group(2));
				String rightQ = cleanIdentifier(equality.group(3));
				String rightC = cleanIdentifier(equality.group(4));

				TableRef leftTable = aliases.get(leftQ.toLowerCase(Locale.ROOT));
				TableRef rightTable = aliases.get(rightQ.toLowerCase(Locale.ROOT));
				if (leftTable == null || rightTable == null || leftTable.key().equals(rightTable.key())) {
					continue;
				}
				String expression = leftQ + "." + leftC + " = " + rightQ + "." + rightC;
				relations.add(new JoinRelation(leftQ, leftC, rightQ, rightC, expression));
			}
		}
		return relations.stream().distinct().toList();
	}

	private String findActualTable(String rawTable, Set<String> actualTables) {
		for (String table : actualTables) {
			if (table.equalsIgnoreCase(rawTable)) {
				return table;
			}
			String[] p = splitQualified(table);
			if (p[p.length - 1].equalsIgnoreCase(rawTable)) {
				return table;
			}
		}
		return null;
	}

	private String[] splitQualified(String value) {
		return value.replace("\"", "").split("\\.");
	}

	private String cleanIdentifierPath(String value) {
		if (value == null) {
			return null;
		}
		
		return value.replaceAll("\\s*\\.\\s*", ".").replace("\"", "");
	}

	private String cleanIdentifier(String value) {
		if (value == null) {
			return null;
		}
		
		return value.replace("\"", "");
	}
}
