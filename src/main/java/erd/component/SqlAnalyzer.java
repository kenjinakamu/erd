package erd.component;

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
	private static final String COLUMN_QUALIFIER = QUALIFIED;

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
			"(?is)(" + COLUMN_QUALIFIER + ")\\s*\\.\\s*(" + IDENT + ")\\s*=\\s*" +
					"(" + COLUMN_QUALIFIER + ")\\s*\\.\\s*(" + IDENT + ")");

	private static final Pattern QUALIFIED_COLUMN_PATTERN = Pattern.compile(
			"(?is)(" + COLUMN_QUALIFIER + ")\\s*\\.\\s*(" + IDENT + ")");

	private static final Pattern BARE_IDENTIFIER_PATTERN = Pattern.compile(
			"(?i)(?<![A-Za-z0-9_$\\.])(" + IDENT + ")(?![A-Za-z0-9_$\\.])");

	private static final Pattern STRING_OR_COMMENT_PATTERN = Pattern.compile(
			"(?s)'(?:''|[^'])*'|--[^\\r\\n]*|/\\*.*?\\*/");

	private static final Pattern TABLE_DECLARATION_PATTERN = Pattern.compile(
			"(?is)\\b(?:from|join)\\s+" + QUALIFIED +
					"(?:\\s+(?:as\\s+)?" + IDENT + ")?");

	private static final Set<String> RESERVED = Set.of(
			"select", "distinct", "all", "from", "as", "on", "using", "where", "join",
			"left", "right", "full", "inner", "cross", "outer", "natural", "group", "by",
			"order", "having", "limit", "offset", "fetch", "first", "next", "rows", "row",
			"only", "union", "except", "intersect", "asc", "desc", "nulls", "last",
			"case", "when", "then", "else", "end", "and", "or", "not", "is", "null",
			"true", "false", "in", "exists", "between", "like", "ilike", "escape",
			"over", "partition", "filter", "within", "window", "range", "groups", "current",
			"unbounded", "preceding", "following", "with", "recursive", "materialized",
			"lateral", "values", "any", "some", "cast", "collate");

	/**
	 * SQLを解析
	 */
	public SqlAnalysis analyze(String sql) {
		validateSelect(sql);

		Set<String> parserTables;
		try {
			parserTables = new LinkedHashSet<>(TablesNamesFinder.findTables(sql));
		} catch (JSQLParserException e) {
			throw new IllegalArgumentException("SQLを解析できません: " + e.getMessage(), e);
		}

		// FROM/JOINに明示された名前を優先する。
		// JSqlParserのテーブル名抽出結果が環境・構文によって未修飾名になる場合でも、
		// SQLに書かれた schema.table のschemaを失わないようにする。
		Set<String> actualTables = mergeDeclaredTableNames(sql, parserTables);

		Map<String, TableRef> aliases = extractAliases(sql, actualTables);
		List<JoinRelation> relations = extractJoinRelations(sql, aliases);
		Map<String, Set<String>> referencedColumnsByTable = extractQualifiedReferencedColumns(sql, aliases);
		Set<String> unqualifiedReferencedColumns = extractUnqualifiedReferencedColumns(sql);
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

		return new SqlAnalysis(actualTables, aliases, relations, referencedColumnsByTable,
				unqualifiedReferencedColumns, warnings.stream().distinct().toList());
	}

	private Set<String> mergeDeclaredTableNames(String sql, Set<String> parserTables) {
		Set<String> declaredTables = new LinkedHashSet<>();
		Matcher matcher = TABLE_PATTERN.matcher(maskStringsAndComments(sql));
		while (matcher.find()) {
			String table = normalizeTableIdentifierPath(matcher.group(1));
			if (table != null && !table.isBlank()) {
				declaredTables.add(table);
			}
		}

		if (declaredTables.isEmpty()) {
			return parserTables;
		}

		Set<String> result = new LinkedHashSet<>(declaredTables);
		for (String parserTable : parserTables) {
			String parserSimpleName = simpleTableName(parserTable);
			boolean representedByDeclaredTable = declaredTables.stream()
					.anyMatch(table -> simpleTableName(table).equalsIgnoreCase(parserSimpleName));
			if (!representedByDeclaredTable) {
				result.add(parserTable);
			}
		}
		return result;
	}

	private String simpleTableName(String value) {
		String[] parts = splitQualified(value);
		return parts[parts.length - 1];
	}

	private Map<String, Set<String>> extractQualifiedReferencedColumns(String sql,
																	   Map<String, TableRef> aliases) {
		Map<String, Set<String>> result = new LinkedHashMap<>();
		Matcher matcher = QUALIFIED_COLUMN_PATTERN.matcher(maskStringsAndComments(sql));
		while (matcher.find()) {
			String qualifier = cleanIdentifierPath(matcher.group(1));
			String column = cleanIdentifier(matcher.group(2));
			TableRef table = aliases.get(qualifier.toLowerCase(Locale.ROOT));
			if (table == null) {
				continue;
			}
			result.computeIfAbsent(table.key(), key -> new LinkedHashSet<>())
					.add(column.toLowerCase(Locale.ROOT));
		}
		return result;
	}

	private Set<String> extractUnqualifiedReferencedColumns(String sql) {
		String masked = maskStringsAndComments(sql);
		masked = maskPattern(masked, TABLE_DECLARATION_PATTERN);
		masked = maskPattern(masked, QUALIFIED_COLUMN_PATTERN);

		Set<String> result = new LinkedHashSet<>();
		Matcher matcher = BARE_IDENTIFIER_PATTERN.matcher(masked);
		while (matcher.find()) {
			String identifier = cleanIdentifier(matcher.group(1));
			String normalized = identifier.toLowerCase(Locale.ROOT);
			if (RESERVED.contains(normalized)) {
				continue;
			}
			if (isFunctionName(masked, matcher.end()) || isAliasDefinition(masked, matcher.start())) {
				continue;
			}
			result.add(normalized);
		}
		return result;
	}

	private String maskStringsAndComments(String sql) {
		return maskPattern(sql, STRING_OR_COMMENT_PATTERN);
	}

	private String maskPattern(String value, Pattern pattern) {
		StringBuilder masked = new StringBuilder(value);
		Matcher matcher = pattern.matcher(value);
		while (matcher.find()) {
			for (int i = matcher.start(); i < matcher.end(); i++) {
				masked.setCharAt(i, ' ');
			}
		}
		return masked.toString();
	}

	private boolean isFunctionName(String sql, int identifierEnd) {
		int index = identifierEnd;
		while (index < sql.length() && Character.isWhitespace(sql.charAt(index))) {
			index++;
		}
		return index < sql.length() && sql.charAt(index) == '(';
	}

	private boolean isAliasDefinition(String sql, int identifierStart) {
		String prefix = sql.substring(Math.max(0, identifierStart - 8), identifierStart);
		return prefix.matches("(?is).*\\bas\\s+$");
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
		Set<String> ambiguousSimpleNames = new LinkedHashSet<>();
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

			result.put(matchedActual.toLowerCase(Locale.ROOT), ref);
			registerSimpleName(result, ambiguousSimpleNames, name, ref);
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
			result.putIfAbsent(table.toLowerCase(Locale.ROOT), ref);
			registerSimpleName(result, ambiguousSimpleNames, name, ref);
		}
		return result;
	}

	private void registerSimpleName(Map<String, TableRef> aliases, Set<String> ambiguousSimpleNames,
									String name, TableRef ref) {
		String key = name.toLowerCase(Locale.ROOT);
		if (ambiguousSimpleNames.contains(key)) {
			return;
		}
		TableRef existing = aliases.get(key);
		if (existing != null && !existing.key().equals(ref.key())) {
			aliases.remove(key);
			ambiguousSimpleNames.add(key);
			return;
		}
		aliases.putIfAbsent(key, ref);
	}

	private List<JoinRelation> extractJoinRelations(String sql, Map<String, TableRef> aliases) {
		List<JoinRelation> relations = new ArrayList<>();
		Matcher joinMatcher = JOIN_BLOCK_PATTERN.matcher(sql);
		while (joinMatcher.find()) {
			String onExpression = joinMatcher.group(1);
			Matcher equality = COLUMN_EQUALITY_PATTERN.matcher(onExpression);
			while (equality.find()) {
				String leftQ = cleanIdentifierPath(equality.group(1));
				String leftC = cleanIdentifier(equality.group(2));
				String rightQ = cleanIdentifierPath(equality.group(3));
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

	private String normalizeTableIdentifierPath(String value) {
		if (value == null) {
			return null;
		}
		// DB検索側で引用識別子かどうかを判定できるよう、二重引用符は保持する。
		return value.trim().replaceAll("\\s*\\.\\s*", ".");
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
