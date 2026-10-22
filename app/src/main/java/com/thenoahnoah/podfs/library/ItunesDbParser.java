package com.thenoahnoah.podfs.library;

import androidx.annotation.NonNull;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Read-only parser for the classic iTunesDB chunk format used by iPod Classic. */
public final class ItunesDbParser {
    private static final int MAX_CHUNK_DEPTH = 32;

    private ItunesDbParser() {
    }

    @NonNull
    public static List<IpodTrack> parse(@NonNull byte[] data) {
        if (data.length < 12 || !marker(data, 0, "mhbd")) {
            throw new IllegalArgumentException("Not an iTunesDB database");
        }
        long databaseLength = uint32(data, 8);
        long headerLength = uint32(data, 4);
        if (headerLength < 12 || databaseLength < headerLength
                || databaseLength > data.length || databaseLength > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Invalid iTunesDB root chunk");
        }
        List<IpodTrack> tracks = new ArrayList<>();
        try {
            parseChunks(data, 0, (int) databaseLength, 0, tracks);
        } catch (IllegalArgumentException ignored) {
            tracks.clear();
            parseLooseTrackRecords(data, (int) databaseLength, tracks);
        }
        return tracks;
    }

    private static void parseLooseTrackRecords(byte[] data, int limit,
                                               List<IpodTrack> tracks) {
        int offset = 0;
        while (offset + 12 <= limit) {
            String marker = markerName(data, offset);
            if (!"mhia".equals(marker) && !"mhit".equals(marker)) {
                offset++;
                continue;
            }
            long headerLength = uint32(data, offset + 4);
            long totalLength = uint32(data, offset + 8);
            if (headerLength < 12 || totalLength < headerLength
                    || totalLength > limit - offset || headerLength > totalLength) {
                offset++;
                continue;
            }
            try {
                tracks.add(parseTrack(data, offset, (int) headerLength,
                        offset + (int) totalLength));
                offset += (int) totalLength;
            } catch (IllegalArgumentException ignored) {
                offset++;
            }
        }
    }

    private static int parseChunks(byte[] data, int start, int limit, int depth,
                                   List<IpodTrack> tracks) {
        if (depth > MAX_CHUNK_DEPTH) {
            throw new IllegalArgumentException("iTunesDB nesting is too deep");
        }
        int offset = start;
        while (offset + 12 <= limit) {
            String marker = markerName(data, offset);
            long headerLength = uint32(data, offset + 4);
            long totalLength = uint32(data, offset + 8);
            if (headerLength < 12 || totalLength < headerLength
                    || totalLength > limit - offset || headerLength > totalLength) {
                throw new IllegalArgumentException("Invalid iTunesDB chunk at " + offset);
            }
            int end = offset + (int) totalLength;
            if ("mhla".equals(marker)) {
                // Some iPod databases store the list length in this field rather
                // than the byte length of all following mhia records.
                parseTrackList(data, offset + (int) headerLength, limit, tracks);
                return limit;
            } else if ("mhit".equals(marker) || "mhia".equals(marker)) {
                tracks.add(parseTrack(data, offset, (int) headerLength, end));
            } else if (!"mhod".equals(marker)) {
                parseChunks(data, offset + (int) headerLength, end, depth + 1, tracks);
            }
            offset = end;
        }
        if (offset != limit) {
            throw new IllegalArgumentException("Trailing bytes in iTunesDB chunk list");
        }
        return offset;
    }

    private static void parseTrackList(byte[] data, int start, int limit,
                                       List<IpodTrack> tracks) {
        int offset = start;
        while (offset + 12 <= limit) {
            String marker = markerName(data, offset);
            if (!"mhia".equals(marker) && !"mhit".equals(marker)) {
                offset++;
                continue;
            }
            long headerLength = uint32(data, offset + 4);
            long totalLength = uint32(data, offset + 8);
            if (headerLength < 12 || totalLength < headerLength
                    || totalLength > limit - offset || headerLength > totalLength) {
                offset++;
                continue;
            }
            int end = offset + (int) totalLength;
            tracks.add(parseTrack(data, offset, (int) headerLength, end));
            offset = end;
        }
    }

    @NonNull
    private static IpodTrack parseTrack(byte[] data, int offset, int headerLength, int end) {
        String title = "";
        String artist = "";
        String album = "";
        String path = "";
        int childStart = offset + headerLength;
        int childOffset = childStart;
        while (childOffset + 12 <= end) {
            long length = uint32(data, childOffset + 8);
            long childHeader = uint32(data, childOffset + 4);
            if (!marker(data, childOffset, "mhod") || childHeader < 24
                    || length < childHeader || length > end - childOffset) {
                throw new IllegalArgumentException("Invalid track metadata chunk");
            }
            int type = (int) uint32(data, childOffset + 12);
            String value = readString(data, childOffset, (int) childHeader,
                    childOffset + (int) length);
            if (type == 1 || type == 200) {
                title = value;
            } else if (type == 2) {
                path = value;
            } else if (type == 3 || type == 202) {
                album = value;
            } else if (type == 4 || type == 201) {
                artist = value;
            }
            childOffset += (int) length;
        }
        if (childOffset != end) {
            throw new IllegalArgumentException("Trailing bytes in track metadata");
        }
        if (path.isEmpty()) {
            path = "";
        }
        long trackId = headerLength >= 0x14 ? uint32(data, offset + 0x10) : 0;
        return new IpodTrack(trackId, title.isEmpty() ? "Untitled" : title,
            artist, album, path, null);
    }

    @NonNull
    private static String readString(byte[] data, int chunkOffset, int headerLength, int end) {
        int prefix = chunkOffset + 0x18;
        if (prefix + 16 > end || prefix + 16 > chunkOffset + headerLength + 1024) {
            return "";
        }
        long encoding = uint32(data, prefix);
        long byteLength = uint32(data, prefix + 4);
        long stringStart = prefix + 16L;
        if (byteLength > end - stringStart || byteLength > Integer.MAX_VALUE) {
            return "";
        }
        int start = (int) stringStart;
        int length = (int) byteLength;
        if (encoding == 1 || encoding == 2) {
            if ((length & 1) != 0) {
                return "";
            }
            return new String(data, start, length, StandardCharsets.UTF_16LE).replace("\u0000", "").trim();
        }
        return new String(data, start, length, StandardCharsets.UTF_8).replace("\u0000", "").trim();
    }

    private static boolean marker(byte[] data, int offset, String expected) {
        return offset + 4 <= data.length && markerName(data, offset).equals(expected);
    }

    @NonNull
    private static String markerName(byte[] data, int offset) {
        return new String(data, offset, 4, StandardCharsets.US_ASCII);
    }

    private static long uint32(byte[] data, int offset) {
        return (data[offset] & 0xffL)
                | ((data[offset + 1] & 0xffL) << 8)
                | ((data[offset + 2] & 0xffL) << 16)
                | ((data[offset + 3] & 0xffL) << 24);
    }
}