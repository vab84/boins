package io.boins.server.s3;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.function.BiFunction;

/**
 * ListObjects scan over a bucket's sorted key view: prefix filtering, delimiter grouping
 * into common prefixes, pagination. Shared by ListObjectsV1 and V2.
 */
public final class S3Listing {

    /** A listed object. */
    public record Item(String key, long size, long lastModified, String etag) {
    }

    /**
     * @param contents       objects in this page
     * @param commonPrefixes delimiter-grouped prefixes in this page
     * @param truncated      whether more results exist
     * @param nextToken      resume token (the last emitted key or common prefix); {@code null}
     *                       when not truncated
     */
    public record Result(List<Item> contents, List<String> commonPrefixes, boolean truncated, String nextToken) {
    }

    private S3Listing() {
    }

    /**
     * Scans {@code keys} and produces one listing page.
     *
     * @param keys      sorted key → blob id view
     * @param prefix    only keys starting with this prefix; may be empty
     * @param delimiter groups keys sharing the substring between the prefix and the first
     *                  delimiter occurrence into common prefixes; may be empty (no grouping)
     * @param after     resume token: scan strictly after this key or common-prefix group;
     *                  may be empty
     * @param maxKeys   page size (contents + common prefixes)
     * @param resolver  loads item details for (key, blobId); returns {@code null} to skip
     *                  the key (e.g. the blob vanished between scan and resolve)
     */
    public static Result list(NavigableMap<String, Long> keys, String prefix, String delimiter,
                              String after, int maxKeys, BiFunction<String, Long, Item> resolver) {
        List<Item> contents = new ArrayList<>();
        List<String> commonPrefixes = new ArrayList<>();
        if (maxKeys <= 0) {
            return new Result(contents, commonPrefixes, false, null);
        }

        NavigableMap<String, Long> scan = keys;
        if (!after.isEmpty()) {
            // Resuming after a common-prefix group means skipping every key inside it:
            // '￿' sorts after any character that can follow the group prefix.
            String resumeFrom = !delimiter.isEmpty() && after.endsWith(delimiter) ? after + '￿' : after;
            scan = scan.tailMap(resumeFrom, false);
        }
        if (!prefix.isEmpty() && (after.isEmpty() || prefix.compareTo(after) > 0)) {
            scan = scan.tailMap(prefix, true);
        }

        String lastEmitted = null;
        String openGroup = null; // common prefix currently being skipped through
        for (Map.Entry<String, Long> entry : scan.entrySet()) {
            String key = entry.getKey();
            if (!prefix.isEmpty() && !key.startsWith(prefix)) {
                break; // sorted scan has left the prefix range
            }
            if (openGroup != null && key.startsWith(openGroup)) {
                continue; // already emitted this group's common prefix
            }
            String group = groupOf(key, prefix, delimiter);
            if (group != null) {
                if (contents.size() + commonPrefixes.size() == maxKeys) {
                    return new Result(contents, commonPrefixes, true, lastEmitted);
                }
                commonPrefixes.add(group);
                lastEmitted = group;
                openGroup = group;
            } else {
                if (contents.size() + commonPrefixes.size() == maxKeys) {
                    return new Result(contents, commonPrefixes, true, lastEmitted);
                }
                Item item = resolver.apply(key, entry.getValue());
                if (item == null) {
                    continue;
                }
                contents.add(item);
                lastEmitted = key;
                openGroup = null;
            }
        }
        return new Result(contents, commonPrefixes, false, null);
    }

    /** The common-prefix group of {@code key}, or {@code null} if the key is a direct entry. */
    private static String groupOf(String key, String prefix, String delimiter) {
        if (delimiter.isEmpty()) {
            return null;
        }
        int index = key.indexOf(delimiter, prefix.length());
        return index < 0 ? null : key.substring(0, index + delimiter.length());
    }
}
