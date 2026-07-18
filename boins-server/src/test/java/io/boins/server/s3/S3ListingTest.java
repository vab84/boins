package io.boins.server.s3;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.NavigableMap;
import java.util.TreeMap;
import java.util.function.BiFunction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class S3ListingTest {

    private static final BiFunction<String, Long, S3Listing.Item> RESOLVER =
            (key, blobId) -> new S3Listing.Item(key, blobId, 0L, "etag-" + blobId);

    private static NavigableMap<String, Long> keys(String... keys) {
        TreeMap<String, Long> map = new TreeMap<>();
        long id = 0L;
        for (String key : keys) {
            map.put(key, id++);
        }
        return map;
    }

    private static List<String> contentKeys(S3Listing.Result result) {
        return result.contents().stream().map(S3Listing.Item::key).toList();
    }

    @Test
    void plainListing() {
        S3Listing.Result result = S3Listing.list(keys("a", "b", "c"), "", "", "", 1_000, RESOLVER);
        assertEquals(List.of("a", "b", "c"), contentKeys(result));
        assertFalse(result.truncated());
        assertNull(result.nextToken());
    }

    @Test
    void prefixFiltering() {
        S3Listing.Result result = S3Listing.list(
                keys("logs/1", "logs/2", "photos/1", "readme"), "logs/", "", "", 1_000, RESOLVER);
        assertEquals(List.of("logs/1", "logs/2"), contentKeys(result));
    }

    @Test
    void delimiterGroupsCommonPrefixes() {
        S3Listing.Result result = S3Listing.list(
                keys("a/x", "a/y", "b/z", "top.txt"), "", "/", "", 1_000, RESOLVER);
        assertEquals(List.of("top.txt"), contentKeys(result));
        assertEquals(List.of("a/", "b/"), result.commonPrefixes());
    }

    @Test
    void delimiterWithPrefix() {
        S3Listing.Result result = S3Listing.list(
                keys("d/a/1", "d/a/2", "d/b/1", "d/direct"), "d/", "/", "", 1_000, RESOLVER);
        assertEquals(List.of("d/direct"), contentKeys(result));
        assertEquals(List.of("d/a/", "d/b/"), result.commonPrefixes());
    }

    @Test
    void paginationAcrossKeys() {
        NavigableMap<String, Long> map = keys("k1", "k2", "k3", "k4", "k5");
        S3Listing.Result page1 = S3Listing.list(map, "", "", "", 2, RESOLVER);
        assertEquals(List.of("k1", "k2"), contentKeys(page1));
        assertTrue(page1.truncated());
        S3Listing.Result page2 = S3Listing.list(map, "", "", page1.nextToken(), 2, RESOLVER);
        assertEquals(List.of("k3", "k4"), contentKeys(page2));
        S3Listing.Result page3 = S3Listing.list(map, "", "", page2.nextToken(), 2, RESOLVER);
        assertEquals(List.of("k5"), contentKeys(page3));
        assertFalse(page3.truncated());
    }

    @Test
    void paginationResumesAfterCommonPrefixGroup() {
        NavigableMap<String, Long> map = keys("a/1", "a/2", "a/3", "b/1", "c.txt");
        S3Listing.Result page1 = S3Listing.list(map, "", "/", "", 1, RESOLVER);
        assertEquals(List.of(), contentKeys(page1));
        assertEquals(List.of("a/"), page1.commonPrefixes());
        assertTrue(page1.truncated());
        assertEquals("a/", page1.nextToken());

        // Resuming after "a/" must skip all of a/1..a/3 and land on the b/ group.
        S3Listing.Result page2 = S3Listing.list(map, "", "/", page1.nextToken(), 1, RESOLVER);
        assertEquals(List.of("b/"), page2.commonPrefixes());
        assertTrue(page2.truncated());

        S3Listing.Result page3 = S3Listing.list(map, "", "/", page2.nextToken(), 1, RESOLVER);
        assertEquals(List.of("c.txt"), contentKeys(page3));
        assertFalse(page3.truncated());
    }

    @Test
    void maxKeysZeroReturnsNothing() {
        S3Listing.Result result = S3Listing.list(keys("a", "b"), "", "", "", 0, RESOLVER);
        assertTrue(result.contents().isEmpty());
        assertFalse(result.truncated());
    }

    @Test
    void vanishedBlobsAreSkipped() {
        BiFunction<String, Long, S3Listing.Item> flaky =
                (key, blobId) -> key.equals("gone") ? null : RESOLVER.apply(key, blobId);
        S3Listing.Result result = S3Listing.list(keys("alive", "gone", "ok"), "", "", "", 1_000, flaky);
        assertEquals(List.of("alive", "ok"), contentKeys(result));
    }

    @Test
    void startAfterSkipsEarlierKeys() {
        S3Listing.Result result = S3Listing.list(keys("a", "b", "c", "d"), "", "", "b", 1_000, RESOLVER);
        assertEquals(List.of("c", "d"), contentKeys(result));
    }
}
