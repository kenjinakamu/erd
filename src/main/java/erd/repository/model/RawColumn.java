package erd.repository.model;

public record RawColumn(
		/** カラム名 */
		String name,
		/** データ型 */
		String type,
		/** NULL許容 */
		boolean nullable,
		/** カラムコメント */
		String comment) {
}
