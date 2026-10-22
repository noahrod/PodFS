package com.thenoahnoah.podfs;

import com.thenoahnoah.podfs.library.IpodTrack;
import com.thenoahnoah.podfs.library.ItunesDbParser;

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
}
