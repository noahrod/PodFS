package com.thenoahnoah.podfs.library;

import androidx.annotation.NonNull;
import java.util.Collections;
import java.util.List;

public final class IpodScanResult {
    private final boolean looksLikeIpod;
    private final List<IpodTrack> tracks;
    private final List<IpodPlaylist> playlists;
    private final String message;

    public IpodScanResult(boolean looksLikeIpod, @NonNull List<IpodTrack> tracks,
                          @NonNull String message) {
        this(looksLikeIpod, tracks, List.of(), message);
    }

    public IpodScanResult(boolean looksLikeIpod, @NonNull List<IpodTrack> tracks,
                          @NonNull List<IpodPlaylist> playlists, @NonNull String message) {
        this.looksLikeIpod = looksLikeIpod;
        this.tracks = Collections.unmodifiableList(tracks);
        this.playlists = Collections.unmodifiableList(playlists);
        this.message = message;
    }

    public boolean looksLikeIpod() {
        return looksLikeIpod;
    }

    @NonNull
    public List<IpodTrack> getTracks() {
        return tracks;
    }

    @NonNull
    public List<IpodPlaylist> getPlaylists() {
        return playlists;
    }

    @NonNull
    public String getMessage() {
        return message;
    }
}
