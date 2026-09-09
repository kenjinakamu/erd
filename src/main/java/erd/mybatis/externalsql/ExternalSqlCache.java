package erd.mybatis.externalsql;

import java.util.Map;

import erd.mybatis.model.ExternalSql;

final class ExternalSqlCache {

	private static volatile Map<String, ExternalSql> cache = Map.of();
	private static volatile boolean initialized;

	private ExternalSqlCache() {
	}

	static synchronized void initialize(Map<String, ExternalSql> sqlMap) {
		if (initialized) {
			throw new IllegalStateException("ExternalSqlCache is already initialized.");
		}
		cache = Map.copyOf(sqlMap);
		initialized = true;
	}

	static ExternalSql get(String path) {
		if (!initialized) {
			throw new IllegalStateException("ExternalSqlCache has not been initialized.");
		}
		ExternalSql externalSql = cache.get(path);
		if (externalSql == null) {
			throw new IllegalStateException("External SQL is not registered: " + path);
		}
		return externalSql;
	}
}
