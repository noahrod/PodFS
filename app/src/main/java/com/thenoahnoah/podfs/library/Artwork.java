package com.thenoahnoah.podfs.library;

import androidx.annotation.NonNull;

public final class Artwork {
    private final int width;
    private final int height;
    private final byte[] rgb;

    public Artwork(int width, int height, @NonNull byte[] rgb) {
        this.width = width;
        this.height = height;
        this.rgb = rgb;
    }

    public int getWidth() {
        return width;
    }

    public int getHeight() {
        return height;
    }

    @NonNull
    public byte[] getRgb() {
        return rgb;
    }
}