package com.thenoahnoah.podfs.library;

import androidx.annotation.NonNull;

public final class IpodTrack {
    private final long trackId;
    /** 64-bit database id (mhit +0x70). Joins to ArtworkDB mhii track refs. */
    private final long dbId;
    /** dbId whose ArtworkDB entry holds this track's cover, or 0. */
    private final long artworkDbId;
    private final int trackNumber;
    private final String title;
    private final String artist;
    private final String album;
    private final String path;
    private final Artwork artwork;

    public IpodTrack(@NonNull String title, @NonNull String path) {
        this(0, 0, title, "", "", path, null);
    }

    public IpodTrack(@NonNull String title, @NonNull String artist, @NonNull String album,
                     @NonNull String path) {
        this(0, 0, title, artist, album, path, null);
    }

    public IpodTrack(long trackId, @NonNull String title, @NonNull String artist,
                     @NonNull String album, @NonNull String path, Artwork artwork) {
        this(trackId, 0, title, artist, album, path, artwork);
    }

    public IpodTrack(long trackId, int trackNumber, @NonNull String title,
                     @NonNull String artist, @NonNull String album,
                     @NonNull String path, Artwork artwork) {
        this(trackId, 0, trackNumber, title, artist, album, path, artwork);
    }

    public IpodTrack(long trackId, long dbId, int trackNumber, @NonNull String title,
                     @NonNull String artist, @NonNull String album,
                     @NonNull String path, Artwork artwork) {
        this(trackId, dbId, dbId, trackNumber, title, artist, album, path, artwork);
    }

    private IpodTrack(long trackId, long dbId, long artworkDbId, int trackNumber,
                      @NonNull String title, @NonNull String artist, @NonNull String album,
                      @NonNull String path, Artwork artwork) {
        this.trackId = trackId;
        this.dbId = dbId;
        this.artworkDbId = artworkDbId;
        this.trackNumber = trackNumber;
        this.title = title;
        this.artist = artist;
        this.album = album;
        this.path = path;
        this.artwork = artwork;
    }

    public long getTrackId() {
        return trackId;
    }

    public long getDbId() {
        return dbId;
    }

    public long getArtworkDbId() {
        return artworkDbId;
    }

    public IpodTrack withArtworkDbId(long id) {
        return new IpodTrack(trackId, dbId, id, trackNumber, title, artist, album, path, artwork);
    }

    public int getTrackNumber() {
        return trackNumber;
    }

    @NonNull
    public String getTitle() {
        return title;
    }

    @NonNull
    public String getArtist() {
        return artist;
    }

    @NonNull
    public String getAlbum() {
        return album;
    }

    @NonNull
    public String getPath() {
        return path;
    }

    public Artwork getArtwork() {
        return artwork;
    }
}
