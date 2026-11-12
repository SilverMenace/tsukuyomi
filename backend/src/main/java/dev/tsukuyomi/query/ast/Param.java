package dev.tsukuyomi.query.ast;

/**
 * A value that came from the request.
 *
 * This is the only AST type allowed to carry request text, and the SQL builder
 * only ever emits it as a "?" placeholder with the text sent separately as a
 * bind parameter. A null text means SQL NULL.
 */
public record Param(String text) {
}
