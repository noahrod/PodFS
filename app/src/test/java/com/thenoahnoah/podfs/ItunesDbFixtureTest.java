package com.thenoahnoah.podfs;

import com.thenoahnoah.podfs.library.IpodTrack;
import com.thenoahnoah.podfs.library.ItunesDbParser;
import com.thenoahnoah.podfs.library.ArtworkDbParser;
import com.thenoahnoah.podfs.library.Artwork;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;

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

    private static File findFixture() {
        String[] candidates = {
                "example/iTunes/iTunes/iTunesDB",
                "../example/iTunes/iTunes/iTunesDB",
                "../../example/iTunes/iTunes/iTunesDB"
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
                "example/Artwork/ArtworkDB",
                "../example/Artwork/ArtworkDB",
                "../../example/Artwork/ArtworkDB"
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
