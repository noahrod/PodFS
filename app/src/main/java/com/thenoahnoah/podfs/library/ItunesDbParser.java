package com.thenoahnoah.podfs.library;

import androidx.annotation.NonNull;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

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

    /**
     * Reads user playlists (mhyp records). The master playlist, which lists every track
     * and is flagged at mhyp+0x14, is skipped. Copies of the same playlist that appear in
     * several playlist sections are merged by their persistent ID.
     */
    @NonNull
    public static List<IpodPlaylist> parsePlaylists(@NonNull byte[] data) {
        List<IpodPlaylist> playlists = new ArrayList<>();
        if (data.length < 12 || !marker(data, 0, "mhbd")) {
            return playlists;
        }
        long databaseLength = Math.min(uint32(data, 8), data.length);
        Set<Long> seen = new HashSet<>();
        int offset = 0;
        while (offset + 12 <= databaseLength) {
            if (!marker(data, offset, "mhyp")) {
                offset++;
                continue;
            }
            long headerLength = uint32(data, offset + 4);
            long totalLength = uint32(data, offset + 8);
            if (headerLength < 0x20 || totalLength < headerLength
                    || totalLength > databaseLength - offset) {
                offset++;
                continue;
            }
            int end = offset + (int) totalLength;
            IpodPlaylist playlist = parsePlaylist(data, offset, (int) headerLength, end);
            // Empty entries are the built-in library lists (Music, Movies, ...) and carry no tracks.
            if (playlist != null && !playlist.getTrackIds().isEmpty()
                    && seen.add(playlist.getPersistentId())) {
                playlists.add(playlist);
            }
            offset = end;
        }
        return playlists;
    }

    private static IpodPlaylist parsePlaylist(byte[] data, int offset, int headerLength, int end) {
        if (uint32(data, offset + 0x14) != 0) {
            return null;
        }
        long persistentId = uint32(data, offset + 0x18) | (uint32(data, offset + 0x1c) << 32);
        String name = "";
        List<Long> trackIds = new ArrayList<>();
        int childOffset = offset + headerLength;
        while (childOffset + 12 <= end) {
            long childHeader = uint32(data, childOffset + 4);
            long length = uint32(data, childOffset + 8);
            if (childHeader < 16 || length < childHeader || length > end - childOffset) {
                return null;
            }
            if (marker(data, childOffset, "mhod")) {
                if (uint32(data, childOffset + 12) == 1) {
                    name = readString(data, childOffset, (int) childHeader,
                            childOffset + (int) length);
                }
            } else if (marker(data, childOffset, "mhip")) {
                if (childHeader >= 0x1c) {
                    trackIds.add(uint32(data, childOffset + 0x18));
                }
            }
            // mhip records carry their own mhod children; skipping by length covers them.
            childOffset += (int) length;
        }
        return new IpodPlaylist(name.isEmpty() ? "Untitled Playlist" : name, persistentId, trackIds);
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
        int trackNumber = headerLength >= 0x18 ? (int) uint32(data, offset + 0x14) : 0;
        long dbId = headerLength >= 0x78 ? uint64(data, offset + 0x70) : 0;
        return new IpodTrack(trackId, dbId, trackNumber, title.isEmpty() ? "Untitled" : title,
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

    private static long uint64(byte[] data, int offset) {
        return uint32(data, offset) | (uint32(data, offset + 4) << 32);
    }

    private static long uint32(byte[] data, int offset) {
        return (data[offset] & 0xffL)
                | ((data[offset + 1] & 0xffL) << 8)
                | ((data[offset + 2] & 0xffL) << 16)
                | ((data[offset + 3] & 0xffL) << 24);
    }
}