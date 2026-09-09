package erd.service.model;

import java.util.List;
import java.util.Map;

public record SourceModel(
		List<JavaComponent> components,
		Map<String, JavaComponent> componentsByQualifiedName,
		Map<String, List<JavaComponent>> componentsByTypeName,
		List<String> warnings) {
}
