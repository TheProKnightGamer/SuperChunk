package dev.superchunk.worldgen;

/**
 * {@link FeatureRegionReads#outsideRegion}: every way 1.21.1 (and a Lithium fork's
 * {@code gen.chunk_region}) refuses a chunk outside a WorldGenRegion is recognised, wrapped or not,
 * and nothing else is. Reports every failing case, then exits non-zero.
 */
public final class FeatureRegionReadsTest {

    private static final String OUTSIDE = "Requested chunk unavailable during world generation";

    private static int checks;
    private static int failures;

    public static void main(String[] args) {
        // vanilla WorldGenRegion.getChunk (outside the region and wrong status share one message)
        expect(true, new IllegalStateException(OUTSIDE));
        // Radium/Canary-style ChunkRegionMixin (getBlockState/getChunk overwrites)
        expect(true, new NullPointerException("No chunk exists at [5, 7]"));
        // wrapped the way feature placement and the chunk pipeline rethrow them
        expect(true, new RuntimeException("Feature placement", new NullPointerException("No chunk exists at [5, 7]")));
        expect(true, new java.util.concurrent.CompletionException(new IllegalStateException(OUTSIDE)));
        // not a region refusal: other types, other messages, no message
        expect(false, new NullPointerException());
        expect(false, new NullPointerException("Cannot invoke \"Object.hashCode()\" because \"key\" is null"));
        expect(false, new IllegalStateException(OUTSIDE + " | 1 2"));
        expect(false, new RuntimeException(OUTSIDE));
        expect(false, new RuntimeException("Some other failure"));
        expect(false, new IllegalArgumentException("No chunk exists at [5, 7]"));
        if (failures > 0) {
            System.err.println("FeatureRegionReads: " + failures + " of " + checks + " cases FAILED");
            System.exit(1);
        }
        System.out.println("FeatureRegionReads: " + checks + " refusal-matching cases passed");
    }

    private static void expect(boolean outside, Throwable t) {
        checks++;
        if (FeatureRegionReads.outsideRegion(t) != outside) {
            System.err.println("FAIL: outsideRegion(" + t + ") should be " + outside);
            failures++;
        }
    }
}
