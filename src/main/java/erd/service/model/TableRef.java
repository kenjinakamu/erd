package erd.service.model;

public record TableRef(String schema, String name, String alias, String qualifiedName) {
	public String key() {
		return qualifiedName.toLowerCase(java.util.Locale.ROOT);
	}
}
