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
    private static final String CACHE_VERSION = "v5:";
    private static final Map<String, Map<Long, ArtworkDbParser.Location>> ARTWORK_INDEX_CACHE =
            new ConcurrentHashMap<>();
    private IpodScanner() {
    }

    /** Tracks and playlists read together from one iTunesDB. */
    private static final class LibraryData {
        final List<IpodTrack> tracks;
        final List<IpodPlaylist> playlists;

        LibraryData(List<IpodTrack> tracks, List<IpodPlaylist> playlists) {
            this.tracks = tracks;
            this.playlists = playlists;
        }

        static LibraryData empty() {
            return new LibraryData(new ArrayList<>(), new ArrayList<>());
        }
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
        LibraryData library = readCachedLibrary(context, rootUri, database);
        if (library == null || library.tracks.isEmpty()) {
            library = readDatabase(context, root);
            writeCachedLibrary(context, rootUri, database, library);
        }
        List<IpodTrack> tracks = new ArrayList<>(library.tracks);
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
        return new IpodScanResult(true, tracks, library.playlists, message);
    }

    private static LibraryData readCachedLibrary(@NonNull Context context, @NonNull Uri rootUri,
                                                 DocumentFile database) {
        if (database == null) {
            return null;
        }
        SharedPreferences preferences = context.getSharedPreferences("podfs_scan_cache", Context.MODE_PRIVATE);
        String encoded = preferences.getString(cacheKey(rootUri, database), null);
        if (encoded == null) {
            return null;
        }
        try {
            JSONObject cached = new JSONObject(encoded);
            JSONArray trackArray = cached.getJSONArray("tracks");
            List<IpodTrack> tracks = new ArrayList<>();
            for (int index = 0; index < trackArray.length(); index++) {
                JSONObject item = trackArray.getJSONObject(index);
                tracks.add(new IpodTrack(item.getLong("id"), item.optLong("dbId", 0),
                        item.optInt("trackNumber", 0), item.getString("title"),
                        item.getString("artist"), item.getString("album"),
                        item.getString("path"), null));
            }
            List<IpodPlaylist> playlists = new ArrayList<>();
            JSONArray playlistArray = cached.optJSONArray("playlists");
            if (playlistArray != null) {
                for (int index = 0; index < playlistArray.length(); index++) {
                    JSONObject item = playlistArray.getJSONObject(index);
                    JSONArray ids = item.getJSONArray("trackIds");
                    List<Long> trackIds = new ArrayList<>();
                    for (int id = 0; id < ids.length(); id++) {
                        trackIds.add(ids.getLong(id));
                    }
                    playlists.add(new IpodPlaylist(item.getString("name"), item.getLong("id"), trackIds));
                }
            }
            return new LibraryData(tracks, playlists);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static void writeCachedLibrary(@NonNull Context context, @NonNull Uri rootUri,
                                           DocumentFile database, @NonNull LibraryData library) {
        if (database == null || library.tracks.isEmpty()) {
            return;
        }
        try {
            JSONArray trackArray = new JSONArray();
            for (IpodTrack track : library.tracks) {
                JSONObject item = new JSONObject();
                item.put("id", track.getTrackId());
                item.put("dbId", track.getDbId());
                item.put("trackNumber", track.getTrackNumber());
                item.put("title", track.getTitle());
                item.put("artist", track.getArtist());
                item.put("album", track.getAlbum());
                item.put("path", track.getPath());
                trackArray.put(item);
            }
            JSONArray playlistArray = new JSONArray();
            for (IpodPlaylist playlist : library.playlists) {
                JSONObject item = new JSONObject();
                item.put("name", playlist.getName());
                item.put("id", playlist.getPersistentId());
                JSONArray ids = new JSONArray();
                for (long trackId : playlist.getTrackIds()) {
                    ids.put(trackId);
                }
                item.put("trackIds", ids);
                playlistArray.put(item);
            }
            JSONObject cached = new JSONObject();
            cached.put("tracks", trackArray);
            cached.put("playlists", playlistArray);
            context.getSharedPreferences("podfs_scan_cache", Context.MODE_PRIVATE)
                    .edit().putString(cacheKey(rootUri, database), cached.toString()).apply();
        } catch (Exception ignored) {
            // A cache failure must never prevent a normal scan.
        }
    }

    @NonNull
    private static String cacheKey(@NonNull Uri rootUri, @NonNull DocumentFile database) {
        return CACHE_VERSION + rootUri + ":" + database.length() + ":" + database.lastModified();
    }

    @NonNull
    private static LibraryData readDatabase(@NonNull Context context,
                                            @NonNull DocumentFile root) {
        DocumentFile database = childFile(root, "iPod_Control/iTunes/iTunesDB");
        if (database == null) {
            return LibraryData.empty();
        }
        try (InputStream input = context.getContentResolver().openInputStream(database.getUri())) {
            if (input == null) {
                return LibraryData.empty();
            }
            byte[] data = readAll(input);
            List<IpodTrack> parsed = ItunesDbParser.parse(data);
            List<IpodPlaylist> playlists = ItunesDbParser.parsePlaylists(data);
            Map<String, Map<String, DocumentFile>> directoryCache = new HashMap<>();
            List<IpodTrack> resolved = new ArrayList<>();
            for (IpodTrack track : parsed) {
                if (!track.getPath().toLowerCase().endsWith(".mp3")) {
                    continue;
                }
                DocumentFile media = childFileCached(root, databasePath(track.getPath()),
                        directoryCache);
                if (media != null && media.isFile()) {
                    resolved.add(new IpodTrack(track.getTrackId(), track.getDbId(), track.getTrackNumber(),
                        track.getTitle(), track.getArtist(), track.getAlbum(), media.getUri().toString(), null));
                }
            }
            return new LibraryData(resolved, playlists);
        } catch (IOException | IllegalArgumentException ignored) {
            return LibraryData.empty();
        }
    }

    public static Artwork loadArtwork(@NonNull Context context, @NonNull Uri rootUri,
                                      @NonNull IpodTrack track) {
        if (track.getDbId() == 0) {
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
            if (index == null) {
                return null;
            }
            ARTWORK_INDEX_CACHE.put(key, index);
        }
        return readCover(context, root, index.get(track.getDbId()));
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

    /** Returns the parsed ArtworkDB index, or null when the database cannot be read. */
    private static Map<Long, ArtworkDbParser.Location> readArtwork(@NonNull Context context,
                                                                    @NonNull DocumentFile root) {
        DocumentFile database = childFile(root, "iPod_Control/Artwork/ArtworkDB");
        if (database == null) {
            return null;
        }
        try (InputStream input = context.getContentResolver().openInputStream(database.getUri())) {
            return input == null ? null : ArtworkDbParser.index(readAll(input));
        } catch (IOException | RuntimeException ignored) {
            return null;
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
        } catch (IOException | RuntimeException ignored) {
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
