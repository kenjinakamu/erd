package erd.service.model;

public record JoinRelation(
		String leftQualifier,
		String leftColumn,
		String rightQualifier,
		String rightColumn,
		String expression) {
}
