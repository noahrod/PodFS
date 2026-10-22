package com.thenoahnoah.podfs;

import com.thenoahnoah.podfs.library.IpodScanner;
import com.thenoahnoah.podfs.library.IpodTrack;
import com.thenoahnoah.podfs.library.ItunesDbParser;
import com.thenoahnoah.podfs.library.ArtworkDbParser;
import com.thenoahnoah.podfs.library.Artwork;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class ItunesDbFixtureTest {
    @Test
    public void parsesCopiedIpodDatabase() throws IOException {
        File fixture = findFixture();
        assertTrue("Copied iTunesDB fixture is missing", fixture.isFile());

        List<IpodTrack> tracks = ItunesDbParser.parse(Files.readAllBytes(fixture.toPath()));

        assertFalse("The fixture should contain tracks", tracks.isEmpty());
        boolean foundMediaTrack = false;
        for (IpodTrack track : tracks) {
            assertNotNull(track.getTitle());
            if (!track.getPath().isEmpty()) {
                foundMediaTrack = true;
                break;
            }
        }
        assertTrue("The fixture should contain a media path", foundMediaTrack);
        assertTrue("The fixture should preserve track numbers", tracks.get(0).getTrackNumber() > 0);
    }

    @Test
    public void indexesCopiedArtworkDatabase() throws IOException {
        File fixture = findArtworkFixture();
        assertTrue("Copied ArtworkDB fixture is missing", fixture.isFile());

        java.util.Map<Long, ArtworkDbParser.Location> locations = ArtworkDbParser.index(
                Files.readAllBytes(fixture.toPath()));

        assertFalse("ArtworkDB should contain artwork locations; count=" + locations.size(), locations.isEmpty());
        ArtworkDbParser.Location first = locations.get(1L);
        assertNotNull("ArtworkDB should map track 1; keys=" + locations.keySet(), first);
        assertTrue("Selected artwork path: " + first.getPath(), first.getPath().endsWith("F1060_1.ithmb"));
        assertTrue(first.getSize() > 0);
        assertTrue(first.getWidth() > 0);
        assertTrue(first.getHeight() > 0);

        File shard = new File(fixture.getParentFile(), first.getPath().replace(":", ""));
        assertTrue("Referenced iTHMB shard is missing", shard.isFile());
        byte[] shardData = Files.readAllBytes(shard.toPath());
        int start = (int) first.getOffset();
        int end = start + (int) first.getSize();
        assertTrue("Artwork range exceeds iTHMB shard", start >= 0 && end <= shardData.length);
        byte[] payload = java.util.Arrays.copyOfRange(shardData, start, end);
        Artwork artwork = ArtworkDbParser.decodeRgb565(payload, first.getWidth(), first.getHeight());
        assertTrue(artwork.getRgb().length > 0);
    }

    @Test
    public void resolvesCoversForIpodTracks() throws IOException {
        File database = findFixture();
        File artworkDb = findArtworkFixture();
        assertTrue("Copied iTunesDB fixture is missing", database.isFile());
        assertTrue("Copied ArtworkDB fixture is missing", artworkDb.isFile());

        List<IpodTrack> tracks = ItunesDbParser.parse(Files.readAllBytes(database.toPath()));
        java.util.Map<Long, ArtworkDbParser.Location> locations = ArtworkDbParser.index(
                Files.readAllBytes(artworkDb.toPath()));
        byte[] shardData = Files.readAllBytes(new File(artworkDb.getParentFile(), "F1060_1.ithmb").toPath());

        int resolved = 0;
        for (IpodTrack track : tracks) {
            ArtworkDbParser.Location location = locations.get(track.getDbId());
            if (location == null) {
                continue;
            }
            int start = (int) location.getOffset();
            int end = start + (int) location.getSize();
            assertTrue("Artwork range exceeds shard for track " + track.getDbId(),
                    start >= 0 && end <= shardData.length);
            byte[] payload = java.util.Arrays.copyOfRange(shardData, start, end);
            Artwork artwork = ArtworkDbParser.decodeRgb565(payload, location.getWidth(), location.getHeight());
            assertTrue("Decoded cover is blank for track " + track.getDbId(),
                    hasVisiblePixels(artwork.getRgb()));
            resolved++;
        }
        assertEquals("Every ArtworkDB entry should join to one iTunesDB track by dbId",
                locations.size(), resolved);
    }

    @Test
    public void linksAlbumCoverToEveryTrackOfAlbum() throws IOException {
        List<IpodTrack> tracks = ItunesDbParser.parse(Files.readAllBytes(findFixture().toPath()));
        java.util.Map<Long, ArtworkDbParser.Location> locations = ArtworkDbParser.index(
                Files.readAllBytes(findArtworkFixture().toPath()));

        List<IpodTrack> linked = IpodScanner.linkAlbumArtwork(tracks, locations);

        int withArtwork = 0;
        IpodTrack owner = null;
        for (IpodTrack track : linked) {
            if (track.getArtworkDbId() != 0) {
                withArtwork++;
            }
            if (track.getDbId() == 1L) {
                owner = track;
            }
        }
        assertTrue("Most tracks should resolve a cover; count=" + withArtwork, withArtwork >= 4000);
        assertNotNull("Track 1 should be in the fixture", owner);
        for (IpodTrack track : linked) {
            if (track.getAlbum().equals(owner.getAlbum()) && track.getArtist().equals(owner.getArtist())) {
                assertEquals("Track without its own cover: " + track.getTitle(), 1L, track.getArtworkDbId());
            }
        }
    }

    @Test
    public void parsesCopiedPlaylists() throws IOException {
        File database = findFixture();
        assertTrue("Copied iTunesDB fixture is missing", database.isFile());

        byte[] data = Files.readAllBytes(database.toPath());
        List<IpodTrack> tracks = ItunesDbParser.parse(data);
        List<com.thenoahnoah.podfs.library.IpodPlaylist> playlists = ItunesDbParser.parsePlaylists(data);

        assertTrue("Expected the two user playlists; count=" + playlists.size(), playlists.size() == 2);
        java.util.Set<Long> trackIds = new java.util.HashSet<>();
        for (IpodTrack track : tracks) {
            trackIds.add(track.getTrackId());
        }
        java.util.List<Integer> sizes = new java.util.ArrayList<>();
        for (com.thenoahnoah.podfs.library.IpodPlaylist playlist : playlists) {
            assertFalse("Playlist has no name", playlist.getName().isEmpty());
            sizes.add(playlist.getTrackIds().size());
            for (Long trackId : playlist.getTrackIds()) {
                assertTrue("Playlist references unknown track " + trackId, trackIds.contains(trackId));
            }
        }
        java.util.Collections.sort(sizes);
        assertEquals(java.util.Arrays.asList(40, 46), sizes);
    }

    private static boolean hasVisiblePixels(byte[] rgb) {
        for (byte value : rgb) {
            if (value != 0) {
                return true;
            }
        }
        return false;
    }

    private static File findFixture() {
        String[] candidates = {
                "example/iPod_Control/iTunes/iTunesDB",
                "../example/iPod_Control/iTunes/iTunesDB",
                "../../example/iPod_Control/iTunes/iTunesDB"
        };
        for (String candidate : candidates) {
            File file = new File(candidate);
            if (file.isFile()) {
                return file;
            }
        }
        return new File(candidates[0]);
    }

    private static File findArtworkFixture() {
        String[] candidates = {
                "example/iPod_Control/Artwork/ArtworkDB",
                "../example/iPod_Control/Artwork/ArtworkDB",
                "../../example/iPod_Control/Artwork/ArtworkDB"
        };
        for (String candidate : candidates) {
            File file = new File(candidate);
            if (file.isFile()) {
                return file;
            }
        }
        return new File(candidates[0]);
    }
}
