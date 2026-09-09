package erd.service.model;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

public record JavaComponent(
		String packageName,
		String simpleName,
		String qualifiedName,
		ComponentLayer layer,
		Path file,
		Map<String, String> dependencyVariables,
		List<String> typeAliases,
		List<JavaMethod> methods,
		List<String> classPaths) {
}
