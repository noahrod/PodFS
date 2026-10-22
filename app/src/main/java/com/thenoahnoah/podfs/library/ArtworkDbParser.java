package com.thenoahnoah.podfs.library;

import androidx.annotation.NonNull;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/** Read-only ArtworkDB index and classic RGB565 cover decoder. */
public final class ArtworkDbParser {
    private ArtworkDbParser() {
    }

    @NonNull
    public static Map<Long, Location> index(@NonNull byte[] data) {
        Map<Long, Location> locations = new HashMap<>();
        try {
            scan(data, 0, data.length, locations);
        } catch (IllegalArgumentException ignored) {
            locations.clear();
        }
        if (locations.isEmpty()) {
            scanLooseImages(data, locations);
        }
        return locations;
    }

    private static void scanLooseImages(byte[] data, Map<Long, Location> result) {
        for (int offset = 0; offset + 12 <= data.length; offset++) {
            if (!marker(data, offset, "mhii")) {
                continue;
            }
            long headerLength = uint32(data, offset + 4);
            long length = uint32(data, offset + 8);
            if (headerLength < 52 || length < headerLength || length > data.length - offset) {
                continue;
            }
            long trackId = uint64(data, offset + 0x14);
            int childStart = offset + (int) headerLength;
            Location location = null;
            try {
                location = findLocation(data, childStart, offset + (int) length);
                if (location == null && childStart >= offset + 8
                        && marker(data, childStart - 8, "mhod")) {
                    location = findLocation(data, childStart - 8, offset + (int) length);
                }
            } catch (IllegalArgumentException ignored) {
                // Continue searching for the next independently bounded mhii.
            }
            if (location == null) {
                location = findLooseLocation(data, childStart, offset + (int) length);
            }
            if (trackId != 0 && location != null) {
                result.put(trackId, location);
            }
        }
    }

    private static Location findLooseLocation(byte[] data, int start, int limit) {
        for (int offset = start; offset + 44 <= limit; offset++) {
            if (!marker(data, offset, "mhni")) {
                continue;
            }
            long headerLength = uint32(data, offset + 4);
            long length = uint32(data, offset + 8);
            if (headerLength < 44 || length < headerLength || length > limit - offset) {
                continue;
            }
            int formatId = (int) uint32(data, offset + 0x10);
            long imageOffset = uint32(data, offset + 0x14);
            long imageSize = uint32(data, offset + 0x18);
            int width = uint16(data, offset + 0x22);
            int height = uint16(data, offset + 0x20);
            String path = findPath(data, offset + (int) headerLength, offset + (int) length);
            if (formatId == 1060 && imageSize > 0 && width > 0 && height > 0 && path != null) {
                return new Location(path, imageOffset, imageSize, width, height);
            }
        }
        return null;
    }

    private static void scan(byte[] data, int start, int limit, Map<Long, Location> result) {
        int offset = start;
        while (offset + 12 <= limit) {
            long length = uint32(data, offset + 8);
            long headerLength = uint32(data, offset + 4);
            if (headerLength < 12 || length < headerLength || length > limit - offset) {
                throw new IllegalArgumentException("Invalid ArtworkDB chunk");
            }
            int end = offset + (int) length;
            if (marker(data, offset, "mhii") && headerLength >= 52) {
                long trackId = uint64(data, offset + 0x14);
                int childStart = offset + (int) headerLength;
                Location location = findLocation(data, childStart, end);
                if (location == null && childStart >= offset + 8
                        && marker(data, childStart - 8, "mhod")) {
                    location = findLocation(data, childStart - 8, end);
                }
                if (trackId != 0 && location != null) {
                    result.put(trackId, location);
                }
            } else if (!marker(data, offset, "mhod")) {
                scan(data, offset + (int) headerLength, end, result);
            }
            offset = end;
        }
        if (offset != limit) {
            throw new IllegalArgumentException("Trailing ArtworkDB bytes");
        }
    }

    private static Location findLocation(byte[] data, int start, int limit) {
        int offset = start;
        while (offset + 12 <= limit) {
            long length = uint32(data, offset + 8);
            long headerLength = uint32(data, offset + 4);
            if (headerLength < 12 || length < headerLength || length > limit - offset) {
                throw new IllegalArgumentException("Invalid ArtworkDB image child");
            }
            int end = offset + (int) length;
            if (marker(data, offset, "mhni") && headerLength >= 44) {
                int formatId = (int) uint32(data, offset + 0x10);
                long imageOffset = uint32(data, offset + 0x14);
                long imageSize = uint32(data, offset + 0x18);
                int width = uint16(data, offset + 0x22);
                int height = uint16(data, offset + 0x20);
                String path = findPath(data, offset + (int) headerLength, end);
                if (formatId == 1060 && imageSize > 0 && width > 0 && height > 0
                        && path != null) {
                    return new Location(path, imageOffset, imageSize, width, height);
                }
            } else {
                Location nested = findLocation(data, offset + (int) headerLength, end);
                if (nested != null) {
                    return nested;
                }
            }
            offset = end;
        }
        return null;
    }

    private static String findPath(byte[] data, int start, int limit) {
        int offset = start;
        while (offset + 12 <= limit) {
            long length = uint32(data, offset + 8);
            long headerLength = uint32(data, offset + 4);
            if (headerLength < 12 || length < headerLength || length > limit - offset) {
                return null;
            }
            int end = offset + (int) length;
            if (marker(data, offset, "mhod") && uint32(data, offset + 0x0c) == 3) {
                return readArtworkString(data, offset, (int) headerLength, end);
            }
            offset = end;
        }
        return null;
    }

    private static String readArtworkString(byte[] data, int offset, int headerLength, int end) {
        int prefix = offset + headerLength;
        if (prefix + 12 > end) {
            return null;
        }
        long byteLength = uint32(data, prefix);
        int encoding = data[prefix + 4] & 0xff;
        int stringStart = prefix + 12;
        if (byteLength > end - stringStart || byteLength > Integer.MAX_VALUE) {
            return null;
        }
        int length = (int) byteLength;
        if (encoding == 2 && (length & 1) != 0) {
            return null;
        }
        String value = new String(data, stringStart, length,
                encoding == 2 ? StandardCharsets.UTF_16LE : StandardCharsets.UTF_8);
        return value.replace("\u0000", "").trim();
    }

    @NonNull
    public static Artwork decodeRgb565(@NonNull byte[] payload, int width, int height) {
        long pixels = (long) width * height;
        if (width <= 0 || height <= 0 || pixels > 4_000_000 || payload.length < pixels * 2) {
            throw new IllegalArgumentException("Invalid RGB565 artwork range");
        }
        byte[] rgb = new byte[(int) pixels * 3];
        for (int index = 0; index < pixels; index++) {
            int value = (payload[index * 2] & 0xff) | ((payload[index * 2 + 1] & 0xff) << 8);
            rgb[index * 3] = (byte) (((value >> 11) & 0x1f) * 255 / 31);
            rgb[index * 3 + 1] = (byte) (((value >> 5) & 0x3f) * 255 / 63);
            rgb[index * 3 + 2] = (byte) ((value & 0x1f) * 255 / 31);
        }
        return new Artwork(width, height, rgb);
    }

    public static final class Location {
        private final String path;
        private final long offset;
        private final long size;
        private final int width;
        private final int height;

        private Location(String path, long offset, long size, int width, int height) {
            this.path = path;
            this.offset = offset;
            this.size = size;
            this.width = width;
            this.height = height;
        }

        @NonNull
        public String getPath() {
            return path;
        }

        public long getOffset() {
            return offset;
        }

        public long getSize() {
            return size;
        }

        public int getWidth() {
            return width;
        }

        public int getHeight() {
            return height;
        }
    }

    private static boolean marker(byte[] data, int offset, String expected) {
        return offset + 4 <= data.length
                && new String(data, offset, 4, StandardCharsets.US_ASCII).equals(expected);
    }

    private static int uint16(byte[] data, int offset) {
        return (data[offset] & 0xff) | ((data[offset + 1] & 0xff) << 8);
    }

    private static long uint32(byte[] data, int offset) {
        return (data[offset] & 0xffL) | ((data[offset + 1] & 0xffL) << 8)
                | ((data[offset + 2] & 0xffL) << 16) | ((data[offset + 3] & 0xffL) << 24);
    }

    private static long uint64(byte[] data, int offset) {
        return uint32(data, offset) | (uint32(data, offset + 4) << 32);
    }
}