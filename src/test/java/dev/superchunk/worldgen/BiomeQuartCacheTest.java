package dev.superchunk.worldgen;

import java.util.Random;
import java.util.concurrent.atomic.AtomicReference;

/** Regression for old managers revisited after the shared table's generation bits wrap. */
public final class BiomeQuartCacheTest {
    public static void main(String[] args) throws InterruptedException {
        BiomeQuartCache cache = BiomeQuartCache.local();
        long oldTicket = cache.claimGeneration();
        Object oldBiome = new Object();
        long oldKey = BiomeQuartCache.encode(oldTicket, 17, -9, 42);
        cache.put(oldKey, oldBiome);
        for (int i = 0; i < 14; i++) {
            cache.claimGeneration();
        }
        long newTicket = cache.claimGeneration();
        long newKey = BiomeQuartCache.encode(newTicket, 17, -9, 42);
        check(newKey == oldKey, "regression must exercise an actual four-bit collision");
        Object newBiome = new Object();
        cache.put(newKey, newBiome);
        check(!cache.isCurrentGeneration(oldTicket), "old manager must detect table reuse");
        oldTicket = cache.claimGeneration();
        long refreshedKey = BiomeQuartCache.encode(oldTicket, 17, -9, 42);
        check(cache.get(refreshedKey) == null, "refreshed manager must miss the other source's entry");

        // A miss resolver may reenter world generation before the outer lookup stores
        // its answer. Reusing the outer low bits must not overwrite the nested result.
        long outerTicket = cache.claimGeneration();
        long outerKey = BiomeQuartCache.encode(outerTicket, 71, 12, -33);
        long nestedTicket;
        do {
            nestedTicket = cache.claimGeneration();
        } while ((nestedTicket & 15L) != (outerTicket & 15L));
        long nestedKey = BiomeQuartCache.encode(nestedTicket, 71, 12, -33);
        Object nestedBiome = new Object();
        cache.put(nestedKey, nestedBiome);
        if (cache.isCurrentGeneration(outerTicket)) {
            cache.put(outerKey, oldBiome);
        }
        check(cache.get(nestedKey) == nestedBiome, "stale outer lookup overwrote a nested manager");

        // Alternate many still-live managers through repeated wraps, all resolving the
        // same quart position to distinct biome holders.
        long[] tickets = new long[41];
        Object[] biomes = new Object[tickets.length];
        for (int i = 0; i < tickets.length; i++) {
            tickets[i] = cache.claimGeneration();
            biomes[i] = new Object();
        }
        Random random = new Random(0x5ca11L);
        for (int i = 0; i < 100_000; i++) {
            int manager = random.nextInt(tickets.length);
            if (!cache.isCurrentGeneration(tickets[manager])) {
                tickets[manager] = cache.claimGeneration();
            }
            long key = BiomeQuartCache.encode(tickets[manager], 17, -9, 42);
            Object hit = cache.get(key);
            check(hit == null || hit == biomes[manager], "biome leaked between managers after wrap");
            cache.put(key, biomes[manager]);
        }

        AtomicReference<BiomeQuartCache> other = new AtomicReference<>();
        Thread worker = new Thread(() -> other.set(BiomeQuartCache.local()));
        worker.start();
        worker.join();
        check(other.get() != cache, "workers must not share cache arrays");
        for (int coordinate : new int[] {Integer.MIN_VALUE, -8_388_609, 8_388_608, Integer.MAX_VALUE}) {
            check(BiomeQuartCache.encode(1, coordinate, 0, 0) == BiomeQuartCache.UNCACHEABLE, "X range");
            check(BiomeQuartCache.encode(1, 0, 0, coordinate) == BiomeQuartCache.UNCACHEABLE, "Z range");
        }
        check(BiomeQuartCache.encode(1, 0, -2049, 0) == BiomeQuartCache.UNCACHEABLE, "Y lower bound");
        check(BiomeQuartCache.encode(1, 0, 2048, 0) == BiomeQuartCache.UNCACHEABLE, "Y upper bound");
        System.out.println("BiomeQuartCache: generation-wrap, 100000 interleaved lookups, thread isolation and bounds passed");
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
