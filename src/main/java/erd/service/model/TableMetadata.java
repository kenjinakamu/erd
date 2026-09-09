package erd.service.model;

import java.util.List;
import java.util.Set;

public record TableMetadata(
		String schema,
		String name,
		List<ColumnMetadata> columnMetadataList,
		Set<String> primaryKeyColumnSet,
		Set<String> uniqueSingleColumnSet) {
	public String qualifiedName() {
		return schema + "." + name;
	}

	public ColumnMetadata column(String columnName) {
		return columnMetadataList.stream()
				.filter(columnMetadata -> columnMetadata.name().equalsIgnoreCase(columnName))
				.findFirst()
				.orElse(null);
	}
}
