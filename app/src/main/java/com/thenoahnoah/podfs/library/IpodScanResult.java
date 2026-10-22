package com.thenoahnoah.podfs.library;

import androidx.annotation.NonNull;
import java.util.Collections;
import java.util.List;

public final class IpodScanResult {
    private final boolean looksLikeIpod;
    private final List<IpodTrack> tracks;
    private final String message;

    public IpodScanResult(boolean looksLikeIpod, @NonNull List<IpodTrack> tracks,
                          @NonNull String message) {
        this.looksLikeIpod = looksLikeIpod;
        this.tracks = Collections.unmodifiableList(tracks);
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
    public String getMessage() {
        return message;
    }
}
