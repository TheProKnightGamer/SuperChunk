package dev.superchunk.worldgen;

/**
 * {@link FeatureRegionReads#outsideRegion}: every way 1.20.1 (and Radium/Canary's
 * {@code gen.chunk_region}) refuses a chunk outside a WorldGenRegion is recognised, wrapped or not,
 * and nothing else is. Reports every failing case, then exits non-zero.
 */
public final class FeatureRegionReadsTest {

    private static int checks;
    private static int failures;

    public static void main(String[] args) {
        // vanilla WorldGenRegion.getChunk (outside the region / wrong status) and getCurrentDifficultyAt
        expect(true, new RuntimeException("We are asking a region for a chunk out of bound | 12 -40"));
        expect(true, new RuntimeException("Chunk is not of correct status. Expecting minecraft:carvers, got minecraft:noise | 1 2"));
        expect(true, new RuntimeException("We are asking a region for a chunk out of bound"));
        // Radium 0.12.4 ChunkRegionMixin (getBlockState/getChunk overwrites)
        expect(true, new NullPointerException("No chunk exists at [5, 7]"));
        // wrapped the way feature placement and the chunk pipeline rethrow them
        expect(true, new RuntimeException("Feature placement", new NullPointerException("No chunk exists at [5, 7]")));
        expect(true, new java.util.concurrent.CompletionException(
                new RuntimeException("We are asking a region for a chunk out of bound | 1 2")));
        // not a region refusal: other types, other messages, no message
        expect(false, new NullPointerException());
        expect(false, new NullPointerException("Cannot invoke \"Object.hashCode()\" because \"key\" is null"));
        expect(false, new IllegalStateException("We are asking a region for a chunk out of bound | 1 2"));
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
