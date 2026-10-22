package com.thenoahnoah.podfs.library;

import android.content.Context;
import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.documentfile.provider.DocumentFile;

import java.util.ArrayList;
import java.util.Comparator;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONObject;

public final class IpodScanner {
    private static final String CACHE_VERSION = "v2:";
    private static final Map<String, Map<Long, ArtworkDbParser.Location>> ARTWORK_INDEX_CACHE =
            new ConcurrentHashMap<>();
    private IpodScanner() {
    }

    @NonNull
    public static IpodScanResult scan(@NonNull Context context, @NonNull Uri rootUri) {
        DocumentFile root = DocumentFile.fromTreeUri(context, rootUri);
        if (root == null || !root.isDirectory()) {
            return new IpodScanResult(false, List.of(), "The selected location is not a directory.");
        }

        boolean hasControl = childDirectory(root, "iPod_Control") != null;
        boolean hasMusic = childDirectory(root, "iPod_Control/Music") != null;
        if (!hasControl || !hasMusic) {
            return new IpodScanResult(false, List.of(),
                    "This volume does not look like an iPod Classic.");
        }

        DocumentFile database = childFile(root, "iPod_Control/iTunes/iTunesDB");
        List<IpodTrack> tracks = readCachedTracks(context, rootUri, database);
        if (tracks.isEmpty()) {
            tracks = readDatabase(context, root);
            writeCachedTracks(context, rootUri, database, tracks);
        }
        boolean usedFallback = tracks.isEmpty();
        if (tracks.isEmpty()) {
            collectMp3s(childDirectory(root, "iPod_Control/Music"), tracks);
        }
        tracks.sort(Comparator.comparing(IpodTrack::getTitle, String.CASE_INSENSITIVE_ORDER));
        String message;
        if (tracks.isEmpty()) {
            message = "No MP3 files found.";
        } else if (usedFallback) {
            message = tracks.size() + " MP3 tracks found using filename fallback. "
                + "iTunesDB metadata was unavailable or unsupported.";
        } else {
            message = tracks.size() + " MP3 tracks loaded from iTunesDB.";
        }
        return new IpodScanResult(true, tracks, message);
    }

    @NonNull
    private static List<IpodTrack> readCachedTracks(@NonNull Context context, @NonNull Uri rootUri,
                                                    DocumentFile database) {
        if (database == null) {
            return new ArrayList<>();
        }
        SharedPreferences preferences = context.getSharedPreferences("podfs_scan_cache", Context.MODE_PRIVATE);
        String key = cacheKey(rootUri, database);
        String encoded = preferences.getString(key, null);
        if (encoded == null) {
            return new ArrayList<>();
        }
        try {
            JSONArray array = new JSONArray(encoded);
            List<IpodTrack> tracks = new ArrayList<>();
            for (int index = 0; index < array.length(); index++) {
                JSONObject item = array.getJSONObject(index);
                tracks.add(new IpodTrack(item.getLong("id"), item.optInt("trackNumber", 0), item.getString("title"),
                        item.getString("artist"), item.getString("album"),
                        item.getString("path"), null));
            }
            return tracks;
        } catch (Exception ignored) {
            return new ArrayList<>();
        }
    }

    private static void writeCachedTracks(@NonNull Context context, @NonNull Uri rootUri,
                                          DocumentFile database, @NonNull List<IpodTrack> tracks) {
        if (database == null || tracks.isEmpty()) {
            return;
        }
        try {
            JSONArray array = new JSONArray();
            for (IpodTrack track : tracks) {
                JSONObject item = new JSONObject();
                item.put("id", track.getTrackId());
                item.put("trackNumber", track.getTrackNumber());
                item.put("title", track.getTitle());
                item.put("artist", track.getArtist());
                item.put("album", track.getAlbum());
                item.put("path", track.getPath());
                array.put(item);
            }
            context.getSharedPreferences("podfs_scan_cache", Context.MODE_PRIVATE)
                    .edit().putString(cacheKey(rootUri, database), array.toString()).apply();
        } catch (Exception ignored) {
            // A cache failure must never prevent a normal scan.
        }
    }

    @NonNull
    private static String cacheKey(@NonNull Uri rootUri, @NonNull DocumentFile database) {
        return CACHE_VERSION + rootUri + ":" + database.length() + ":" + database.lastModified();
    }

    @NonNull
    private static List<IpodTrack> readDatabase(@NonNull Context context,
                                                @NonNull DocumentFile root) {
        DocumentFile database = childFile(root, "iPod_Control/iTunes/iTunesDB");
        if (database == null) {
            return new ArrayList<>();
        }
        try (InputStream input = context.getContentResolver().openInputStream(database.getUri())) {
            if (input == null) {
                return new ArrayList<>();
            }
            byte[] data = readAll(input);
            List<IpodTrack> parsed = ItunesDbParser.parse(data);
            Map<String, Map<String, DocumentFile>> directoryCache = new HashMap<>();
            List<IpodTrack> resolved = new ArrayList<>();
            for (IpodTrack track : parsed) {
                if (!track.getPath().toLowerCase().endsWith(".mp3")) {
                    continue;
                }
                DocumentFile media = childFileCached(root, databasePath(track.getPath()),
                        directoryCache);
                if (media != null && media.isFile()) {
                    resolved.add(new IpodTrack(track.getTrackId(), track.getTrackNumber(), track.getTitle(),
                        track.getArtist(), track.getAlbum(), media.getUri().toString(), null));
                }
            }
            return resolved;
        } catch (IOException | IllegalArgumentException ignored) {
            return new ArrayList<>();
        }
    }

    public static Artwork loadArtwork(@NonNull Context context, @NonNull Uri rootUri,
                                      @NonNull IpodTrack track) {
        if (track.getTrackId() == 0) {
            return null;
        }
        DocumentFile root = DocumentFile.fromTreeUri(context, rootUri);
        if (root == null) {
            return null;
        }
        DocumentFile database = childFile(root, "iPod_Control/Artwork/ArtworkDB");
        if (database == null) {
            return null;
        }
        String key = rootUri + ":" + database.length() + ":" + database.lastModified();
        Map<Long, ArtworkDbParser.Location> index = ARTWORK_INDEX_CACHE.get(key);
        if (index == null) {
            index = readArtwork(context, root);
            ARTWORK_INDEX_CACHE.put(key, index);
        }
        return readCover(context, root, index.get(track.getTrackId()));
    }

    @NonNull
    private static DocumentFile childFileCached(@NonNull DocumentFile root,
                                                @NonNull String path,
                                                @NonNull Map<String, Map<String, DocumentFile>> cache) {
        DocumentFile current = root;
        String currentPath = "";
        for (String part : path.split("/")) {
            Map<String, DocumentFile> children = cache.get(currentPath);
            if (children == null) {
                children = new HashMap<>();
                for (DocumentFile child : current.listFiles()) {
                    if (child.getName() != null) {
                        children.put(child.getName(), child);
                    }
                }
                cache.put(currentPath, children);
            }
            DocumentFile next = children.get(part);
            if (next == null || (!part.equals(path.substring(path.lastIndexOf('/') + 1))
                    && !next.isDirectory())) {
                return null;
            }
            current = next;
            currentPath = currentPath.isEmpty() ? part : currentPath + "/" + part;
        }
        return current;
    }

    @NonNull
    private static Map<Long, ArtworkDbParser.Location> readArtwork(@NonNull Context context,
                                                                    @NonNull DocumentFile root) {
        DocumentFile database = childFile(root, "iPod_Control/Artwork/ArtworkDB");
        if (database == null) {
            return java.util.Collections.emptyMap();
        }
        try (InputStream input = context.getContentResolver().openInputStream(database.getUri())) {
            return input == null ? java.util.Collections.emptyMap()
                    : ArtworkDbParser.index(readAll(input));
        } catch (IOException | IllegalArgumentException ignored) {
            return java.util.Collections.emptyMap();
        }
    }

    private static Artwork readCover(@NonNull Context context, @NonNull DocumentFile root,
                                     ArtworkDbParser.Location location) {
        if (location == null || location.getOffset() < 0 || location.getSize() <= 0
                || location.getSize() > Integer.MAX_VALUE) {
            return null;
        }
        DocumentFile file = childFile(root, "iPod_Control/Artwork/" + databasePath(location.getPath()));
        if (file == null) {
            return null;
        }
        try (InputStream input = context.getContentResolver().openInputStream(file.getUri())) {
            if (input == null || !skipFully(input, location.getOffset())) {
                return null;
            }
            byte[] payload = readExactly(input, (int) location.getSize());
            return ArtworkDbParser.decodeRgb565(payload, location.getWidth(), location.getHeight());
        } catch (IOException | IllegalArgumentException ignored) {
            return null;
        }
    }

    private static boolean skipFully(@NonNull InputStream input, long bytes) throws IOException {
        while (bytes > 0) {
            long skipped = input.skip(bytes);
            if (skipped <= 0) {
                if (input.read() == -1) {
                    return false;
                }
                skipped = 1;
            }
            bytes -= skipped;
        }
        return true;
    }

    @NonNull
    private static byte[] readExactly(@NonNull InputStream input, int size) throws IOException {
        byte[] result = new byte[size];
        int offset = 0;
        while (offset < size) {
            int count = input.read(result, offset, size - offset);
            if (count == -1) {
                throw new IOException("Short artwork range");
            }
            offset += count;
        }
        return result;
    }

    @NonNull
    private static byte[] readAll(@NonNull InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int count;
        while ((count = input.read(buffer)) != -1) {
            output.write(buffer, 0, count);
            if (output.size() > 256 * 1024 * 1024) {
                throw new IOException("iTunesDB is too large");
            }
        }
        return output.toByteArray();
    }

    @NonNull
    private static String databasePath(@NonNull String value) {
        String normalized = value.trim();
        if (normalized.startsWith(":")) {
            normalized = normalized.substring(1);
        }
        return normalized.replace(':', '/');
    }

    private static DocumentFile childDirectory(@NonNull DocumentFile root, @NonNull String path) {
        DocumentFile current = root;
        for (String part : path.split("/")) {
            DocumentFile next = null;
            for (DocumentFile child : current.listFiles()) {
                if (child.isDirectory() && part.equals(child.getName())) {
                    next = child;
                    break;
                }
            }
            if (next == null) {
                return null;
            }
            current = next;
        }
        return current;
    }

    private static DocumentFile childFile(@NonNull DocumentFile root, @NonNull String path) {
        DocumentFile current = root;
        String[] parts = path.split("/");
        for (int index = 0; index < parts.length; index++) {
            DocumentFile next = null;
            for (DocumentFile child : current.listFiles()) {
                if (parts[index].equals(child.getName())
                        && (index == parts.length - 1 || child.isDirectory())) {
                    next = child;
                    break;
                }
            }
            if (next == null) {
                return null;
            }
            current = next;
        }
        return current;
    }

    private static void collectMp3s(DocumentFile directory, List<IpodTrack> tracks) {
        if (directory == null) {
            return;
        }
        for (DocumentFile child : directory.listFiles()) {
            if (child.isDirectory()) {
                collectMp3s(child, tracks);
            } else if (child.getName() != null && child.getName().toLowerCase().endsWith(".mp3")) {
                tracks.add(new IpodTrack(displayName(child.getName()), child.getUri().toString()));
            }
        }
    }

    @NonNull
    private static String displayName(@NonNull String filename) {
        return filename.substring(0, filename.length() - 4).replace('_', ' ');
    }
}
