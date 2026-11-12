package dev.tsukuyomi.query.ast;

/** Comparison operators the API understands. The SQL for each lives in SqlBuilder. */
public enum Operator {

	EQ, NEQ, GT, GTE, LT, LTE, LIKE, ILIKE, IN, IS_NULL, IS_TRUE, IS_FALSE

}
