package erd.service.model;

/**
 * カラムのメタ情報
 */
public record ColumnMetadata(
		/** カラム名 */
		String name,
		/** データ型 */
		String dataType,
		/** NULL許容 */
		boolean nullable,
		/** 主キー */
		boolean primaryKey,
		/** ユニーク */
		boolean unique,
		/** カラムコメント */
		String comment) {
}
