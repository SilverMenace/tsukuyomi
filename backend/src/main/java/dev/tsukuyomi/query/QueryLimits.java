package dev.tsukuyomi.query;

/**
 * Upper bounds on what one request may ask for. Without them a single URL
 * could request an enormous page, a deep tree of expansions or thousands of
 * conditions, and tie up the database for everyone.
 */
public record QueryLimits(int defaultLimit, int maxLimit, int maxOffset, int maxFilters, int maxInValues,
		int maxValueLength, int maxOrderTerms, int maxEmbedDepth, int maxEmbedRows, int maxSelectLength,
		int maxBatchRows) {

	public static final QueryLimits DEFAULT = new QueryLimits(20, 100, 100_000, 20, 100, 2_000, 5, 2, 100, 2_000,
			100);

}
