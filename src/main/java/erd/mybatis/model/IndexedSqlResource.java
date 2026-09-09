package erd.mybatis.model;

import org.springframework.core.io.Resource;

public record IndexedSqlResource(ExternalSql externalSql, Resource resource) {
}
