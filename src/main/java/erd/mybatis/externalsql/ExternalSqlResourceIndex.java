package erd.mybatis.externalsql;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.core.io.Resource;
import org.springframework.core.io.support.ResourcePatternResolver;

import erd.mybatis.model.ExternalSql;
import erd.mybatis.model.IndexedSqlResource;

final class ExternalSqlResourceIndex {

	private static final String SQL_PATTERN = "classpath*:sql/**/*.sql";
	private static final String SQL_MARKER = "/sql/";
	private static final String JAR_SQL_MARKER = "!/sql/";

	private final Map<String, List<IndexedSqlResource>> resourcesByPath;

	private ExternalSqlResourceIndex(Map<String, List<IndexedSqlResource>> resourcesByPath) {
		this.resourcesByPath = resourcesByPath;
	}

	static ExternalSqlResourceIndex scan(ResourcePatternResolver resolver) {
		try {
			Map<String, List<IndexedSqlResource>> index = new LinkedHashMap<>();
			for (Resource resource : resolver.getResources(SQL_PATTERN)) {
				String path = logicalPath(resource);
				String sql = resource.getContentAsString(StandardCharsets.UTF_8);
				ExternalSql externalSql = new ExternalSql(path, sql);
				index.computeIfAbsent(path, ignored -> new ArrayList<>())
						.add(new IndexedSqlResource(externalSql, resource));
			}
			return new ExternalSqlResourceIndex(index);
		} catch (IOException e) {
			throw new IllegalStateException("Failed to scan external SQL resources.", e);
		}
	}

	Map<String, List<IndexedSqlResource>> resourcesByPath() {
		return resourcesByPath;
	}

	List<IndexedSqlResource> find(String path) {
		return resourcesByPath.getOrDefault(path, List.of());
	}

	private static String logicalPath(Resource resource) throws IOException {
		String location = resource.getURI().toString();
		int jarSqlIndex = location.lastIndexOf(JAR_SQL_MARKER);
		if (0 <= jarSqlIndex) {
			return "sql/" + location.substring(jarSqlIndex + JAR_SQL_MARKER.length());
		}
		int sqlIndex = location.lastIndexOf(SQL_MARKER);
		if (0 <= sqlIndex) {
			return "sql/" + location.substring(sqlIndex + SQL_MARKER.length());
		}
		throw new IllegalStateException("Cannot determine SQL resource path: " + resource.getDescription());
	}
}
