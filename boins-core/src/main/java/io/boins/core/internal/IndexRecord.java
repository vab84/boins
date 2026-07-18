package io.boins.core.internal;

import java.nio.ByteBuffer;

/**
 * Fixed-size index record describing one blob. 80 bytes on disk.
 *
 * <p>Layout (offsets in bytes):</p>
 * <pre>
 *  0  short blobFileIndex   blob file ordinal inside the repository
 *  2  long  blobPosition    content offset inside the blob file
 * 10  long  blobSize        content length in bytes
 * 18  long  cellSize        allocated cell length (>= blobSize; reusable after delete)
 * 26  long  createTime      epoch millis
 * 34  long  deleteTime      epoch millis, -1 while alive
 * 42  long  keyPosition     string-heap position of the key, -1 if none
 * 50  long  metaPosition    string-heap position of encoded user metadata, -1 if none
 * 58  short contentTypeId   content type dictionary id, -1 if none
 * 60  byte[16] md5          content digest (meaning depends on partCount)
 * 76  int   partCount       -1 no digest, 0 single upload, >0 multipart part count
 * </pre>
 *
 * @param md5 16-byte digest; never {@code null} (all zeros when {@code partCount == -1})
 */
public record IndexRecord(
        short blobFileIndex,
        long blobPosition,
        long blobSize,
        long cellSize,
        long createTime,
        long deleteTime,
        long keyPosition,
        long metaPosition,
        short contentTypeId,
        byte[] md5,
        int partCount) {

    public static final int SIZE = 80;
    public static final int MD5_SIZE = 16;
    /** Field offsets used for partial updates and reads. */
    public static final int OFFSET_CELL_SIZE = 18;
    public static final int OFFSET_DELETE_TIME = 34;

    public static final int NO_DIGEST = -1;
    public static final long NO_HEAP_POSITION = -1L;
    public static final short NO_CONTENT_TYPE = -1;
    public static final long ALIVE = -1L;

    public IndexRecord {
        if (md5 == null) {
            md5 = new byte[MD5_SIZE];
        } else if (md5.length != MD5_SIZE) {
            throw new IllegalArgumentException("md5 must be exactly " + MD5_SIZE + " bytes");
        }
    }

    public boolean deleted() {
        return deleteTime >= 0L;
    }

    /**
     * A record slot that was reserved but never written (all zeros), which can appear
     * after a crash between reserving and writing. Distinguished from real records by
     * {@code createTime == 0}.
     */
    public boolean isHole() {
        return createTime == 0L;
    }

    public void writeTo(ByteBuffer bb) {
        bb.putShort(blobFileIndex);
        bb.putLong(blobPosition);
        bb.putLong(blobSize);
        bb.putLong(cellSize);
        bb.putLong(createTime);
        bb.putLong(deleteTime);
        bb.putLong(keyPosition);
        bb.putLong(metaPosition);
        bb.putShort(contentTypeId);
        bb.put(md5);
        bb.putInt(partCount);
    }

    public static IndexRecord readFrom(ByteBuffer bb) {
        short blobFileIndex = bb.getShort();
        long blobPosition = bb.getLong();
        long blobSize = bb.getLong();
        long cellSize = bb.getLong();
        long createTime = bb.getLong();
        long deleteTime = bb.getLong();
        long keyPosition = bb.getLong();
        long metaPosition = bb.getLong();
        short contentTypeId = bb.getShort();
        byte[] md5 = new byte[MD5_SIZE];
        bb.get(md5);
        int partCount = bb.getInt();
        return new IndexRecord(blobFileIndex, blobPosition, blobSize, cellSize, createTime, deleteTime,
                keyPosition, metaPosition, contentTypeId, md5, partCount);
    }
}
