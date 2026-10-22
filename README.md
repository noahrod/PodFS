# PodFS

PodFS is an open-source Android application for browsing and playing music from
a sixth-generation iPod Classic. The iPod is treated as a normal storage volume
that Android has mounted. PodFS does not currently implement a raw USB protocol
or modify the iPod filesystem.

This README is also a development handoff document. It describes the behavior
that exists in the code today, the boundaries that are intentional, and the
work that remains. Do not treat the roadmap as implemented functionality.

## Current Status

The project contains a working first vertical slice:

- The user explicitly chooses a mounted volume with Android's Storage Access
  Framework.
- PodFS validates that the selected volume contains `iPod_Control/Music`.
- PodFS reads the classic `iPod_Control/iTunes/iTunesDB` when it is present and
  can parse the supported track metadata, including the copied fixture under
  `example/iTunes/iTunes/iTunesDB`.
- MP3 paths from the database are resolved to files in the selected document
  tree.
- If the database is missing, malformed, unsupported, or produces no resolved
  tracks, PodFS recursively scans `iPod_Control/Music` for `.mp3` files.
- The UI provides Songs, Artists, Albums, and Playlists browse modes.
- Playlists come from iTunesDB `mhyp` records and open into their songs.
- Shuffle plays the current songs, group, or playlist in random order.
- A Settings tab shows a timestamped activity log of scan stages, browsing,
  and playback events. It is saved to app storage across launches, and uncaught
  crashes are recorded with their stack trace. The Email debug log button opens
  a mail draft with the log; without a mail app it falls back to the share sheet.
- Artists and albums open into their associated songs.
- The UI displays title, artist, album metadata, and lazily loaded artwork from
  ArtworkDB/iTHMB when the relevant track row becomes visible.
- Every track of an album shows the album cover, even though ArtworkDB links
  one track per album.
- Tapping a track starts playback. Tapping the playing track pauses or resumes
  it. Playback keeps a queue based on the current track list and supports
  previous, next, automatic advancement, and stop.
- AndroidX MediaCompat publishes the current title, artist, album, playback
  state, album artwork, and transport controls to Android's media notification
  and lock-screen controls.
- A persistent metadata cache avoids reparsing an unchanged `iTunesDB` on later
  scans of the same selected volume.
- Scanning and parsing are read-only.

This is not yet a complete iTunes replacement or a complete iPod database
implementation.

## Product Scope

The original target is a sixth-generation iPod Classic containing music and
artwork. The current scope is deliberately narrow:

- Android app, minimum API 24.
- Standard mounted USB storage only.
- Sixth-generation iPod Classic layout as the primary target.
- MP3 playback only.
- Scan after volume selection and automatically when a remembered volume is
  restored.
- Read-only access; no editing, syncing, deleting, or writing to the iPod.
- iPod-like music browsing currently includes Songs, Artists, Albums, and
  Playlists, with drill-down from an artist, album, or playlist to its songs.

## User Flow

1. Launch the app.
2. Tap **Choose iPod volume**.
3. Select the mounted iPod root directory in the Android document-tree picker.
4. PodFS requests persistent read permission for that URI.
5. PodFS scans on a background coroutine. The UI activity log records scan
  start, completion/failure, and elapsed time.
6. If the volume looks like an iPod, the Songs browse mode is shown.
7. Choose Artists, Albums, or Playlists to browse groups, then select a group
  to see its songs. Shuffle plays them in random order.
8. Tap a track to play or pause it. Use the Now Playing screen or Android's
  media notification for previous, next, pause, and stop.
9. Open the Settings tab to read the debug log.

The selected URI is passed to `DocumentFile.fromTreeUri`. The app does not
assume a filesystem path and does not use direct `java.io.File` access for the
mounted volume.

## Expected iPod Layout

The selected document-tree root should contain a structure similar to:

```text
iPod_Control/
  Music/
    F00/
    F01/
    ...
  iTunes/
    iTunesDB
  Artwork/
    ArtworkDB
    F1060_1.ithmb
    ...
```

Only `iPod_Control` and `iPod_Control/Music` are required for the volume to be
recognized. `iTunesDB` and `ArtworkDB` are optional because the scanner has a
recursive MP3 fallback and treats artwork as optional.

## Architecture

The code is split into a Java library/parser layer and a Kotlin Compose UI
layer.

```text
MainActivity.kt
  -> IpodScanner.scan(context, selectedTreeUri)
      -> DocumentFile traversal
      -> ItunesDbParser.parse(bytes)
      -> MP3 path resolution
      -> IpodScanResult
  -> Compose track list
  -> ArtworkDB lazy artwork loading
  -> queue-aware MediaPlayer playback
  -> MediaSessionCompat notification metadata, artwork, and controls
```

### UI

`MainActivity.kt` owns the current prototype screen:

- launches `OpenDocumentTree`;
- takes persistable read permission;
- launches scanning on `Dispatchers.IO`;
- displays scan status and Songs, Artists, Albums, and Playlists browse modes;
- shows the Settings tab with the newest 20 activity-log lines first, a Load more
  button that reveals 20 more per press, an Email debug log button, and a Delete
  debug log button that asks for confirmation;
- owns the queue-aware `MediaPlayer` controller and media session;
- publishes playback metadata and notification actions to Android;
- converts decoded artwork RGB bytes into an Android `Bitmap` for display.

### Scanner

`library/IpodScanner.java` is the storage boundary. It:

1. Builds a `DocumentFile` tree from the selected URI.
2. Checks for `iPod_Control` and `iPod_Control/Music`.
3. Reads up to 256 MiB for database-like files through a bounded `readAll` helper.
4. Parses `iTunesDB` and resolves supported colon-separated iPod paths to
  document-tree files.
5. Filters database-backed tracks to `.mp3` paths.
6. Caches SAF directory listings while resolving database paths, avoiding a
  full recursive music-file index when iTunesDB succeeds.
7. Caches parsed track metadata using the selected volume URI and database
  fingerprint (length and last-modified time).
8. Loads the ArtworkDB index lazily and reads only the referenced iTHMB byte
  range for a visible track.
9. Falls back to recursive MP3 discovery when database-backed scanning returns
   no tracks.
10. Sorts the main library result by title, case-insensitively. Album drill-down
  uses the iPod database track number, with unknown numbers afterward.
11. Reports each stage (volume check, cache, iTunesDB, ArtworkDB, filename
  fallback) to the activity log through a progress callback.

The scanner deliberately does not load artwork during the initial scan. This
was removed after testing showed that opening and decoding artwork for thousands
of tracks could make the UI appear stuck on `Scanning...`.

Malformed database or artwork data is fail-closed: the affected metadata or
artwork is discarded, and the scanner continues where possible.

### Models

- `IpodScanResult` contains `looksLikeIpod`, an immutable track list, playlists,
  and a user-facing status message.
- `IpodTrack` contains the iTunes track ID, the 64-bit dbid used for the artwork
  join, track number, title, artist, album, resolved media URI/path, and
  optional `Artwork`.
- `IpodPlaylist` contains a name, playlist ID, and ordered track IDs from one
  `mhyp` record.
- `Artwork` contains width, height, and decoded packed RGB bytes.
- `ArtworkDbParser.Location` contains an iTHMB path, byte offset, byte size,
  width, and height.

## Database Support

### iTunesDB

`ItunesDbParser.java` implements a conservative read-only parser for the classic
chunk format:

- validates the `mhbd` root marker;
- validates chunk header and total lengths;
- limits recursive chunk nesting to 32 levels;
- reads `mhit` track chunks;
- reads `mhia` track chunks used by the supplied iPod database;
- reads nested `mhod` values for:
  - type `1`: title;
  - type `2`: media location;
  - type `3`: album;
  - type `4`: artist;
- supports UTF-16LE and UTF-8 metadata strings;
- supports the fixture's metadata IDs `200` (title), `201` (artist), and `202`
  (album), in addition to the older `1`-`4` mapping;
- extracts the 64-bit dbid at `+0x70`, used to associate artwork;
- reads `mhyp` playlist records and skips the master playlist.

The parser respects the declared length of the root `mhbd` chunk instead of
requiring the entire physical file to be part of that chunk. It also handles
the supplied database's `mhla` list layout and bounded recovery through its
padding/alignment bytes. This is still a supported subset, not full iTunesDB
parity. Unsupported layouts or malformed data result in fallback MP3 scanning.

### ArtworkDB and iTHMB

`ArtworkDbParser.java` indexes the artwork relationship used by the current
prototype:

- `mhii` provides the iTunes track reference;
- nested `mhni` provides format, offset, byte size, width, and height;
- nested MHOD type `3` provides the iTHMB shard filename/path;
- format ID `1060` is treated as classic little-endian RGB565;
- the scanner reads only the referenced byte range;
- bounded recovery handles the supplied ArtworkDB's `mhii`/`mhni` alignment
  gaps without weakening normal chunk validation;
- unsupported formats and invalid ranges produce no cover rather than failing
  the music scan.

Artwork is linked per album. ArtworkDB has one `mhii` per album, so
`IpodScanner.linkAlbumArtwork` gives every track the cover of its album. It
matches by album and artist first, then by album alone when that album title
has only one artist.

The decoded artwork is kept in memory as RGB bytes and converted to a Compose
image in the track row. Artwork loading is intentionally lazy so a large
library can appear before all covers are decoded. The current implementation
does not yet support every ArtworkDB format, every row-stride variant, or every
iPod generation.

### Playback and Android controls

`MainActivity.kt` uses AndroidX `MediaSessionCompat` around the existing
`MediaPlayer`. The session exposes title, artist, album, album artwork,
play/pause, previous, next, and stop actions. Artwork is loaded asynchronously
from ArtworkDB and supplied both as session metadata and as the notification's
large icon. The current implementation is process-bound; a future
`MediaSessionService`/foreground-service migration is still needed for fully
reliable playback after the app process is reclaimed.

### PhotosDB

`PhotosDB` is not implemented. Photo browsing is outside the current music
vertical slice.

## Project Structure

```text
PodFS/
  .github/workflows/
    release.yml                    Manual APK release workflow
  build.gradle.kts                 Root Gradle configuration
  settings.gradle.kts              Project and repository settings
  gradle/libs.versions.toml        Dependency and plugin versions
  app/
    build.gradle.kts               Android application configuration
    src/main/
      AndroidManifest.xml          Launcher activity; no raw USB permissions
      java/com/thenoahnoah/podfs/
        MainActivity.kt             Compose UI and MediaPlayer control
        DebugLog.kt                 Persistent debug log and crash capture
        library/
          Artwork.java              Decoded artwork model
          ArtworkDbParser.java      ArtworkDB index and RGB565 decoder
          IpodPlaylist.java         Playlist model
          IpodScanResult.java       Scan result model
          IpodScanner.java          SAF traversal and scan orchestration
          IpodTrack.java            Track model
          ItunesDbParser.java       Read-only iTunesDB parser
        ui/theme/                   Starter Material 3 theme files
      res/                          Android resources
    src/test/                       Parser tests for iTunesDB and ArtworkDB fixtures
    src/androidTest/                Starter instrumentation-test location
```

## Build Requirements

- macOS, Linux, or Windows;
- Android Studio or a compatible JDK/Gradle environment;
- Android SDK platform 36 and build tools available to the Android Gradle
  Plugin;
- a device or emulator for manual UI testing;
- a mounted iPod volume for end-to-end scanning tests.

The project currently uses:

- Android Gradle Plugin `8.13.2`;
- Gradle version supplied by `gradle/wrapper`;
- Kotlin `2.0.21`;
- Java and Kotlin JVM target 11;
- compile SDK 36, target SDK 36, minimum SDK 24;
- Jetpack Compose Material 3;
- `androidx.documentfile:documentfile:1.1.0`.

## Build and Test Commands

From the project root:

```bash
./gradlew :app:assembleDebug
```

This is the current authoritative validation command and has passed during
development.

Run the existing unit-test and instrumentation-test tasks with:

```bash
./gradlew :app:testDebugUnitTest
./gradlew :app:connectedDebugAndroidTest
```

The repository contains parser tests for the copied databases at
`example/iPod_Control/iTunes/iTunesDB` and
`example/iPod_Control/Artwork/ArtworkDB`. The ArtworkDB test also reads the
referenced `F1060_1.ithmb` byte range and decodes it as RGB565. The fixture
tests also check that one album cover reaches every track of its album. No automated test covers scanning through an Android `DocumentFile`
volume.

## GitHub Releases

The **Create release** GitHub Actions workflow can be started manually from
the repository's **Actions** tab. Choose **Run workflow** and provide a release
tag, such as `v1.0.0`; the release title is optional and defaults to
`PodFS release`.

The workflow builds the installable debug APK with `:app:assembleDebug`, then
creates a GitHub release for the supplied tag with automatically generated
release notes. The APK is attached to the release as `PodFS.apk`. This workflow
does not create a signed production release APK.

## Known Limitations

- No raw USB host support; the device must already be mounted by Android.
- No automatic volume detection; the selected volume URI is remembered and
  restored when the app opens.
- No full iTunesDB parser parity with iOpenPod; the parser supports the supplied
  fixture layout and a conservative subset of other layouts.
- No support for iTunesCDB or other database variants.
- MP3 is the only media type accepted from database-backed results.
- Fallback-discovered tracks have filename-derived titles and no artist/album
  metadata.
- The complete hierarchical iPod menu is not implemented. Playlists cover only
  the `mhyp` records read from iTunesDB.
- Playback has queue navigation, notification controls, and automatic
  completion advancement, but still lacks seeking, audio focus policy, and a
  process-independent foreground media service.
- Artwork support is limited to the currently recognized RGB565 format and
  requires a resolvable ArtworkDB relationship. Visible rows load artwork
  lazily, and the player/notification loads the active track's artwork lazily;
  a persistent thumbnail cache is not implemented yet.
- The metadata cache skips unchanged database parsing, but it is not yet a true
  per-track or per-file delta index. Changes to the iPod should invalidate the
  database fingerprint naturally, after which the database is parsed again.
- PhotosDB is not read.
- No write operations are implemented or intended in the current scope.
- No automated scan test runs against a complete mounted sixth-generation iPod
  `DocumentFile` volume; the checked-in iTunesDB and ArtworkDB fixtures cover
  parser-level validation.
- `LICENSE` currently contains a GPL-3.0 notice and link, not the full license
  text.

## Development Rules

Future changes should preserve these boundaries unless the product scope is
deliberately changed:

1. Keep storage access in `IpodScanner`; do not make Compose code parse raw
   database bytes.
2. Keep parsers read-only and defensive. Invalid lengths, offsets, encodings,
   nesting, and paths must not crash a scan.
3. Treat artwork as optional. A missing or unsupported cover must never hide a
   playable track.
4. Resolve iPod database paths through `DocumentFile`; do not assume that a
   `content://` URI can be converted into a normal filesystem path.
5. Add parser tests with small synthetic binary fixtures before expanding
   support for additional database layouts.
6. Preserve the minimum API 24 requirement unless the product decision changes.
7. Do not add write/sync behavior without explicit product and safety review.

## Roadmap

The next useful implementation steps are:

1. Add synthetic unit fixtures for valid and malformed iTunesDB chunks.
2. Add malformed ArtworkDB/iTHMB cases and verify additional track-to-cover
  mappings and format variants.
3. Confirm RGB565 dimensions, row stride, and format variants against real
   sixth-generation device data.
4. Move artwork decoding and caching toward an efficient thumbnail pipeline.
5. Add a true per-file/per-track delta index with validation of cached media
  URIs before reusing cached metadata.
6. Add a foreground `MediaSessionService`, audio focus handling, and seeking.
7. Add the remaining hierarchical iPod menus.
8. Decide whether PhotosDB support is in scope.
9. Expand parser coverage toward the relevant iOpenPod formats while keeping
   attribution and GPL obligations clear.
10. Replace the short license notice with the complete GPL-3.0 text if desired
   for distribution packaging.

## Acknowledgements and License

PodFS is intended to be released under the GNU General Public License, version
3. See [LICENSE](LICENSE).

The following projects informed PodFS:

- [TheRealSavi/iOpenPod](https://github.com/TheRealSavi/iOpenPod) provided
  parser design and iPod database format research. It is licensed under
  GPL-3.0-or-later. Any copied or ported code must retain its license and
  attribution requirements. PodFS is not represented as a complete iOpenPod
  port.
- [ghenry22/substreamer](https://github.com/ghenry22/substreamer) inspired the
  dark library shell, grouped browsing, persistent mini-player, and Now Playing
  visual direction. PodFS uses its own name, branding, implementation, and
  artwork.

PodFS also uses [Jetpack Compose](https://github.com/androidx/androidx),
[AndroidX Media](https://github.com/androidx/androidx), and
[DocumentFile](https://github.com/androidx/androidx) as Android dependencies.
Those libraries remain subject to their own licenses. Inspiration and
dependencies are credited here separately from code that may be copied or
ported.

### Author

PodFS is authored by **Noe Rodriguez**. GitHub: [github.com/noahrod](https://github.com/noahrod).
