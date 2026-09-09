package erd.service.model;

import java.util.List;
import java.util.Map;
import java.util.Set;

public record SqlAnalysis(
		Set<String> tableNames,
		Map<String, TableRef> qualifierToTable,
		List<JoinRelation> relations,
		List<String> warnings) {
}
