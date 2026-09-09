package erd.repository.model;

/**
 * ユニーク制約カラム
 */
public record UniqueConstraintColumn(
		/** カラム名 */
		String columnName,
		/** ユニーク制約構成カラム数 */
		int columnCount) {
}