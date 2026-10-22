package com.thenoahnoah.podfs.library;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** A user playlist from iTunesDB, referencing tracks by their track ID in play order. */
public final class IpodPlaylist {
    private final String name;
    private final long persistentId;
    private final List<Long> trackIds;

    public IpodPlaylist(@NonNull String name, long persistentId, @NonNull List<Long> trackIds) {
        this.name = name;
        this.persistentId = persistentId;
        this.trackIds = Collections.unmodifiableList(new ArrayList<>(trackIds));
    }

    @NonNull
    public String getName() {
        return name;
    }

    public long getPersistentId() {
        return persistentId;
    }

    @NonNull
    public List<Long> getTrackIds() {
        return trackIds;
    }
}
