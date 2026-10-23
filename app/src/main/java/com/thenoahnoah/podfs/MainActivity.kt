package com.thenoahnoah.podfs

import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.os.Build
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.media.MediaBrowserServiceCompat
import androidx.media.MediaBrowserServiceCompat.BrowserRoot
import androidx.media.app.NotificationCompat.MediaStyle
import android.support.v4.media.MediaBrowserCompat
import android.app.PendingIntent
import android.Manifest
import android.content.pm.PackageManager
import android.os.Environment
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.thenoahnoah.podfs.library.IpodScanResult
import com.thenoahnoah.podfs.library.IpodScanner
import com.thenoahnoah.podfs.library.IpodTrack
import com.thenoahnoah.podfs.library.Artwork
import com.thenoahnoah.podfs.ui.theme.PodFSTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(R.style.Theme_PodFS)
        super.onCreate(savedInstanceState)
        DebugLog.init(this)
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf("android.permission.POST_NOTIFICATIONS"), 1001)
        }
        enableEdgeToEdge()
        setContent {
            PodFSTheme {
                PodFsApp()
            }
        }
    }
}

object PlaybackHub {
    private var session: MediaSessionCompat? = null
    private var controller: PlaybackController? = null

    fun session(context: Context): MediaSessionCompat = session ?: MediaSessionCompat(context.applicationContext, "PodFS").also {
        session = it
    }

    fun controller(context: Context): PlaybackController = controller ?: PlaybackController(context.applicationContext, session(context)).also {
        controller = it
    }

    fun existingController(): PlaybackController? = controller
}

class PodFsMediaService : MediaBrowserServiceCompat() {
    override fun onCreate() {
        super.onCreate()
        sessionToken = PlaybackHub.session(this).sessionToken
        PlaybackHub.controller(this).onNotification = { notification, playing ->
            val manager = getSystemService(NotificationManager::class.java)
            when {
                notification == null -> {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    manager.cancel(42)
                }
                playing -> ServiceCompat.startForeground(this, 42, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
                else -> {
                    stopForeground(STOP_FOREGROUND_DETACH)
                    manager.notify(42, notification)
                }
            }
        }
    }

    override fun onGetRoot(clientPackageName: String, clientUid: Int, rootHints: Bundle?): BrowserRoot =
        BrowserRoot("root", null)

    override fun onLoadChildren(parentId: String, result: Result<MutableList<MediaBrowserCompat.MediaItem>>) {
        result.sendResult(mutableListOf())
    }
}

class PlaybackController(
    private val context: Context,
    private val session: MediaSessionCompat
) {
    var onStateChanged: (IpodTrack?, Boolean) -> Unit = { _, _ -> }
    var onLog: (String) -> Unit = {}
    var onNotification: (Notification?, Boolean) -> Unit = { _, _ -> }
    private val notificationManager = context.getSystemService(NotificationManager::class.java)
    private val artworkExecutor = Executors.newSingleThreadExecutor()
    private var player: MediaPlayer? = null
    private var queue = emptyList<IpodTrack>()
    private var index = -1
    private var current: IpodTrack? = null
    private var rootUri: Uri? = null
    private var currentArtwork: Bitmap? = null
    private var isPlaying = false

    init {
        session.setCallback(object : MediaSessionCompat.Callback() {
            override fun onPlay() = resume()
            override fun onPause() = pause()
            override fun onStop() = stop()
            override fun onSkipToNext() = next()
            override fun onSkipToPrevious() = previous()
        })
        session.isActive = true
        if (Build.VERSION.SDK_INT >= 26) {
            notificationManager.createNotificationChannel(NotificationChannel("podfs_playback", "PodFS playback", NotificationManager.IMPORTANCE_LOW))
        }
    }

    fun play(track: IpodTrack, tracks: List<IpodTrack>, rootUri: Uri?) {
        queue = tracks
        this.rootUri = rootUri
        index = queue.indexOfFirst { it.path == track.path }.takeIf { it >= 0 } ?: 0
        onLog("Queue: ${queue.size} tracks.")
        openCurrent()
    }

    fun toggle() { if (player?.isPlaying == true) pause() else resume() }

    fun next() {
        if (index < queue.lastIndex) { index++; openCurrent() }
    }

    fun previous() {
        if (index > 0) { index--; openCurrent() }
    }

    fun pause() {
        player?.pause()
        isPlaying = false
        publishState()
        current?.let { onLog("Paused: ${it.title}") }
    }

    fun resume() {
        player?.start()
        isPlaying = player != null
        publishState()
        if (player != null) current?.let { onLog("Resumed: ${it.title}") }
    }

    fun stop() {
        current?.let { onLog("Stopped: ${it.title}") }
        player?.stop()
        player?.release()
        player = null
        isPlaying = false
        current = null
        session.isActive = false
        onNotification(null, false)
        onStateChanged(null, false)
    }

    private fun openCurrent() {
        val track = queue.getOrNull(index) ?: return
        player?.release()
        player = try { MediaPlayer.create(context, Uri.parse(track.path)) } catch (_: RuntimeException) { null }
        if (player == null) {
            onLog("Playback could not open: ${track.title} (${track.path})")
            onStateChanged(null, false)
            return
        }
        current = track
        currentArtwork = null
        player?.setOnCompletionListener {
            onLog("Finished: ${track.title}")
            if (index < queue.lastIndex) { index++; openCurrent() } else pause()
        }
        player?.start()
        isPlaying = true
        session.isActive = true
        publishState()
        onLog("Playing: ${track.title}")
    }

    private fun publishState() {
        val track = current ?: return
        val actions = PlaybackStateCompat.ACTION_PLAY or PlaybackStateCompat.ACTION_PAUSE or
            PlaybackStateCompat.ACTION_STOP or PlaybackStateCompat.ACTION_SKIP_TO_NEXT or PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS
        session.setPlaybackState(PlaybackStateCompat.Builder().setActions(actions)
            .setState(if (isPlaying) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED,
                player?.currentPosition?.toLong() ?: 0L, 1f).build())
        val metadata = MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, track.title)
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, track.artist)
            .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, track.album).build()
        session.setMetadata(metadata)
        onNotification(buildNotification(track, isPlaying, currentArtwork), isPlaying)
        onStateChanged(track, isPlaying)
        rootUri?.let { uri ->
            artworkExecutor.execute {
                val artwork = IpodScanner.loadArtwork(context, uri, track)
                if (artwork != null && current?.path == track.path) {
                    val bitmap = artworkBitmap(artwork)
                    currentArtwork = bitmap
                    session.setMetadata(MediaMetadataCompat.Builder(metadata)
                        .putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, bitmap)
                        .putBitmap(MediaMetadataCompat.METADATA_KEY_ART, bitmap).build())
                    onNotification(buildNotification(track, isPlaying, bitmap), isPlaying)
                }
            }
        }
    }

    private fun buildNotification(track: IpodTrack, playing: Boolean, artwork: Bitmap? = null) = NotificationCompat.Builder(context, "podfs_playback")
        .setSmallIcon(com.thenoahnoah.podfs.R.drawable.ic_launcher_foreground)
        .setContentTitle(track.title).setContentText(track.artist.ifEmpty { track.album })
        .setLargeIcon(artwork)
        .setVisibility(NotificationCompat.VISIBILITY_PUBLIC).setOngoing(playing)
        .setStyle(MediaStyle().setMediaSession(session.sessionToken).setShowActionsInCompactView(0, 1, 2))
        .addAction(NotificationCompat.Action(android.R.drawable.ic_media_previous, "Previous", actionIntent("previous")))
        .addAction(NotificationCompat.Action(if (playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play, if (playing) "Pause" else "Play", actionIntent(if (playing) "pause" else "play")))
        .addAction(NotificationCompat.Action(android.R.drawable.ic_media_next, "Next", actionIntent("next")))
        .addAction(NotificationCompat.Action(android.R.drawable.ic_menu_close_clear_cancel, "Stop", actionIntent("stop"))).build()

    private fun artworkBitmap(artwork: Artwork): Bitmap {
        val colors = IntArray(artwork.width * artwork.height)
        for (index in colors.indices) {
            val offset = index * 3
            colors[index] = AndroidColor.rgb(artwork.rgb[offset].toInt() and 0xff,
                artwork.rgb[offset + 1].toInt() and 0xff, artwork.rgb[offset + 2].toInt() and 0xff)
        }
        return Bitmap.createBitmap(colors, artwork.width, artwork.height, Bitmap.Config.ARGB_8888)
    }

    private fun actionIntent(action: String): PendingIntent = PendingIntent.getBroadcast(
        context, action.hashCode(), PlaybackActionReceiver.intent(context, action),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
}

class PlaybackActionReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val playback = PlaybackHub.controller(context)
        when (intent.getStringExtra("action")) {
            "previous" -> playback.previous()
            "play" -> playback.resume()
            "pause" -> playback.pause()
            "next" -> playback.next()
            "stop" -> playback.stop()
        }
    }

    companion object {
        fun intent(context: Context, action: String) = Intent(context, PlaybackActionReceiver::class.java).putExtra("action", action)
    }
}

private enum class AppTab(val label: String, val icon: ImageVector) {
    Library("Library", Icons.Default.LibraryMusic),
    NowPlaying("Now Playing", Icons.Default.Headphones),
    Settings("Settings", Icons.Default.Settings)
}

private enum class BrowseMode(val label: String, val icon: ImageVector) {
    Songs("Songs", Icons.Default.MusicNote),
    Artists("Artists", Icons.Default.Person),
    Albums("Albums", Icons.Default.Album),
    Playlists("Playlists", Icons.Default.QueueMusic)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PodFsApp() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val preferences = remember { context.getSharedPreferences("podfs", Context.MODE_PRIVATE) }
    var selectedTab by remember { mutableStateOf(AppTab.Library) }
    var browseMode by remember { mutableStateOf(BrowseMode.Songs) }
    var selectedGroup by remember { mutableStateOf<String?>(null) }
    var scanResult by remember { mutableStateOf<IpodScanResult?>(null) }
    var isScanning by remember { mutableStateOf(false) }
    var playingTrack by remember { mutableStateOf<IpodTrack?>(null) }
    var isPlaying by remember { mutableStateOf(false) }
    var activityLog by remember { mutableStateOf(DebugLog.snapshot()) }
    var selectedVolumeUri by remember {
        mutableStateOf(preferences.getString("selected_volume_uri", null)?.let(Uri::parse))
    }
    var pendingVolume by remember { mutableStateOf<Uri?>(null) }

    fun log(message: String) {
        activityLog = DebugLog.append(message)
    }

    fun shareDebugLog() {
        val subject = "PodFS debug log"
        val body = DebugLog.exportText()
        try {
            context.startActivity(
                Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:"))
                    .putExtra(Intent.EXTRA_SUBJECT, subject)
                    .putExtra(Intent.EXTRA_TEXT, body)
            )
        } catch (_: ActivityNotFoundException) {
            try {
                val share = Intent(Intent.ACTION_SEND).setType("text/plain")
                    .putExtra(Intent.EXTRA_SUBJECT, subject)
                    .putExtra(Intent.EXTRA_TEXT, body)
                context.startActivity(Intent.createChooser(share, "Share debug log"))
            } catch (_: ActivityNotFoundException) {
                log("No app available to share the debug log.")
                return
            }
        }
        log("Debug log shared.")
    }

    val playback = remember {
        context.startService(Intent(context, PodFsMediaService::class.java))
        PlaybackHub.controller(context).also {
            it.onStateChanged = { track, playing ->
                playingTrack = track
                isPlaying = playing
            }
            it.onLog = ::log
        }
    }

    fun scanVolume(uri: Uri) {
        isScanning = true
        log("Starting iPod scan...")
        scope.launch {
            val startedAt = SystemClock.elapsedRealtime()
            try {
                scanResult = withContext(Dispatchers.IO) {
                    IpodScanner.scan(context, uri) { message -> scope.launch { log(message) } }
                }
                val seconds = (SystemClock.elapsedRealtime() - startedAt) / 1000.0
                log("Scan complete in ${"%.1f".format(seconds)}s: ${scanResult?.message}")
            } catch (error: Exception) {
                val message = error.message ?: "storage could not be read"
                log("Scan failed: $message")
                scanResult = IpodScanResult(false, emptyList(), "Scan failed: $message")
            } finally {
                isScanning = false
            }
        }
    }

    fun selectVolume(uri: Uri) {
        preferences.edit().putString("selected_volume_uri", uri.toString()).apply()
        selectedVolumeUri = uri
        if (uri.scheme == "content") {
            try {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: SecurityException) {
                log("This storage does not keep access after restart.")
            }
        }
        log("Selected storage volume.")
        scanVolume(uri)
    }

    fun togglePlayback(track: IpodTrack, queue: List<IpodTrack>) {
        if (playingTrack?.path == track.path) {
            playback.toggle()
            return
        }
        playback.play(track, queue, selectedVolumeUri)
        selectedTab = AppTab.NowPlaying
    }

    fun playShuffled(tracks: List<IpodTrack>) {
        if (tracks.isEmpty()) return
        log("Shuffle started.")
        val shuffled = tracks.shuffled()
        playback.play(shuffled.first(), shuffled, selectedVolumeUri)
        selectedTab = AppTab.NowPlaying
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) selectVolume(uri)
    }

    val storagePermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val volume = pendingVolume
        pendingVolume = null
        if (volume == null) return@rememberLauncherForActivityResult
        if (granted) {
            selectVolume(volume)
        } else {
            log("Storage permission denied; PodFS cannot read the iPod.")
        }
    }

    fun openMountedVolume() {
        val volume = IpodScanner.findMountedVolumes().firstOrNull()
        if (volume == null) {
            log("No iPod found under /mnt/media_rw or /storage. Plug in the iPod and try again.")
            return
        }
        log("Using mounted volume ${volume.path}.")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                log("Grant All files access to PodFS, then tap Choose iPod volume again.")
                try {
                    context.startActivity(
                        Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                            Uri.parse("package:${context.packageName}"))
                    )
                } catch (_: ActivityNotFoundException) {
                    log("This device has no All files access settings screen.")
                }
                return
            }
        } else if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE)
            != PackageManager.PERMISSION_GRANTED) {
            pendingVolume = volume
            storagePermission.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
            return
        }
        selectVolume(volume)
    }

    fun openVolumePicker() {
        log("Opening volume picker.")
        try {
            picker.launch(null)
        } catch (_: ActivityNotFoundException) {
            log("This device has no folder picker.")
            openMountedVolume()
        }
    }

    LaunchedEffect(Unit) {
        val savedUri = preferences.getString("selected_volume_uri", null)?.let(Uri::parse)
        if (savedUri != null) {
            try {
                if (savedUri.scheme == "content") {
                    context.contentResolver.takePersistableUriPermission(savedUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                log("Restored previously selected volume.")
                scanVolume(savedUri)
            } catch (_: SecurityException) {
                preferences.edit().remove("selected_volume_uri").apply()
                log("Saved volume permission is no longer available.")
            }
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("PODFS", fontWeight = FontWeight.Black, letterSpacing = 2.sp)
                        Text(
                            when (selectedTab) {
                                AppTab.Library -> "YOUR LIBRARY"
                                AppTab.NowPlaying -> "NOW PLAYING"
                                AppTab.Settings -> "APP SETTINGS"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                actions = {
                    if (selectedTab == AppTab.Library) {
                        IconButton(onClick = ::openVolumePicker, enabled = !isScanning) {
                            Icon(Icons.Default.Usb, "Choose iPod volume")
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        },
        bottomBar = {
            Column {
                playingTrack?.let { track ->
                    MiniPlayer(track, selectedVolumeUri, isPlaying) {
                        selectedTab = AppTab.NowPlaying
                    }
                }
                NavigationBar(
                    modifier = Modifier.navigationBarsPadding(),
                    containerColor = MaterialTheme.colorScheme.surface
                ) {
                    AppTab.entries.forEach { tab ->
                        NavigationBarItem(
                            selected = selectedTab == tab,
                            onClick = { selectedTab = tab; log("Tab: ${tab.label}") },
                            icon = { Icon(tab.icon, tab.label) },
                            label = { Text(tab.label) }
                        )
                    }
                }
            }
        }
    ) { padding ->
        when (selectedTab) {
            AppTab.Library -> LibraryScreen(
                modifier = Modifier.padding(padding),
                result = scanResult,
                rootUri = selectedVolumeUri,
                isScanning = isScanning,
                browseMode = browseMode,
                selectedGroup = selectedGroup,
                onBrowseMode = { browseMode = it; selectedGroup = null; log("Browse: ${it.label}") },
                onGroup = { selectedGroup = it; log("Opened: $it") },
                onBack = { selectedGroup = null; log("Back to ${browseMode.label}") },
                onChooseVolume = ::openVolumePicker,
                onTrack = ::togglePlayback,
                onShuffle = ::playShuffled
            )
            AppTab.NowPlaying -> NowPlayingScreen(
                modifier = Modifier.padding(padding),
                track = playingTrack,
                rootUri = selectedVolumeUri,
                isPlaying = isPlaying,
                onToggle = { if (playingTrack != null) playback.toggle() },
                onPrevious = playback::previous,
                onNext = playback::next,
                onStop = playback::stop,
                onBrowse = { selectedTab = AppTab.Library }
            )
            AppTab.Settings -> SettingsScreen(
                modifier = Modifier.padding(padding),
                activityLog = activityLog,
                savedVolume = preferences.getString("selected_volume_uri", null) != null,
                onChooseVolume = ::openVolumePicker,
                onShareLog = ::shareDebugLog,
                onClearLog = { activityLog = DebugLog.clear() },
                onClearVolume = {
                    preferences.edit().remove("selected_volume_uri").apply()
                    scanResult = null
                    log("Remembered volume cleared.")
                }
            )
        }
    }
}

@Composable
private fun LibraryScreen(
    modifier: Modifier,
    result: IpodScanResult?,
    rootUri: Uri?,
    isScanning: Boolean,
    browseMode: BrowseMode,
    selectedGroup: String?,
    onBrowseMode: (BrowseMode) -> Unit,
    onGroup: (String) -> Unit,
    onBack: () -> Unit,
    onChooseVolume: () -> Unit,
    onTrack: (IpodTrack, List<IpodTrack>) -> Unit,
    onShuffle: (List<IpodTrack>) -> Unit
) {
    val tracks = result?.tracks ?: emptyList()
    val tracksById = remember(tracks) { tracks.associateBy { it.trackId } }
    val playlistSource = result?.playlists ?: emptyList()
    val playlists = remember(playlistSource, tracksById) {
        playlistSource
            .map { playlist -> PlaylistEntry(playlist.name, playlist.trackIds.mapNotNull { tracksById[it] }) }
            .filter { it.tracks.isNotEmpty() }
    }
    Column(modifier = modifier.fillMaxSize()) {
        if (result == null && !isScanning) {
            EmptyLibrary(onChooseVolume)
        } else {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        if (isScanning) "Scanning your iPod" else "Music library",
                        style = MaterialTheme.typography.headlineMedium
                    )
                    Text(
                        if (isScanning) "Reading tracks and metadata..." else result?.message.orEmpty(),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                if (isScanning) {
                    Icon(Icons.Default.Refresh, "Scanning", tint = MaterialTheme.colorScheme.primary)
                } else {
                    Text("${tracks.size}", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.titleLarge)
                }
            }
            BrowseSelector(browseMode, onBrowseMode)
            if (browseMode == BrowseMode.Playlists) {
                if (selectedGroup != null) {
                    val playlistTracks = playlists.firstOrNull { it.name == selectedGroup }?.tracks.orEmpty()
                    GroupHeader(selectedGroup, onBack)
                    ShuffleRow(playlistTracks, onShuffle)
                    TrackList(playlistTracks, rootUri) { track -> onTrack(track, playlistTracks) }
                } else {
                    ShuffleRow(playlists.flatMap { it.tracks }.distinctBy { it.trackId }, onShuffle)
                    PlaylistList(playlists, onGroup)
                }
            } else if (selectedGroup != null) {
                GroupHeader(selectedGroup, onBack)
                val groupedTracks = tracks.filter { browseMode.matches(it, selectedGroup) }
                    .let { if (browseMode == BrowseMode.Albums) it.sortedWith(compareBy<IpodTrack> { it.trackNumber == 0 }.thenBy { it.trackNumber }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.title }) else it }
                ShuffleRow(groupedTracks, onShuffle)
                TrackList(groupedTracks, rootUri) { track -> onTrack(track, groupedTracks) }
            } else if (browseMode == BrowseMode.Songs) {
                ShuffleRow(tracks, onShuffle)
                TrackList(tracks, rootUri) { track -> onTrack(track, tracks) }
            } else {
                ShuffleRow(tracks, onShuffle)
                GroupList(browseMode, tracks, rootUri, onGroup)
            }
        }
    }
}

private data class PlaylistEntry(val name: String, val tracks: List<IpodTrack>)

@Composable
private fun ShuffleRow(tracks: List<IpodTrack>, onShuffle: (List<IpodTrack>) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Button(onClick = { onShuffle(tracks) }, enabled = tracks.isNotEmpty()) {
            Icon(Icons.Default.Shuffle, null)
            Spacer(Modifier.width(8.dp))
            Text("Shuffle ${tracks.size} songs")
        }
    }
}

@Composable
private fun PlaylistList(entries: List<PlaylistEntry>, onPlaylist: (String) -> Unit) {
    if (entries.isEmpty()) {
        Text("No playlists found on this iPod.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(20.dp))
        return
    }
    LazyColumn(contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        items(entries) { entry ->
            Row(modifier = Modifier.fillMaxWidth().clickable { onPlaylist(entry.name) }.padding(horizontal = 12.dp, vertical = 15.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(entry.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${entry.tracks.size} songs", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                }
                Text("›", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.titleLarge)
            }
        }
    }
}

@Composable
private fun EmptyLibrary(onChooseVolume: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(28.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
            Icon(Icons.Default.Storage, null, modifier = Modifier.padding(22.dp).size(42.dp), tint = MaterialTheme.colorScheme.primary)
        }
        Spacer(Modifier.height(20.dp))
        Text("Connect your iPod", style = MaterialTheme.typography.headlineMedium)
        Text("Choose its mounted storage volume to bring your music into PodFS.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(22.dp))
        Button(onClick = onChooseVolume) {
            Icon(Icons.Default.FolderOpen, null)
            Spacer(Modifier.width(8.dp))
            Text("Choose iPod volume")
        }
    }
}

@Composable
private fun BrowseSelector(selected: BrowseMode, onSelected: (BrowseMode) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        BrowseMode.entries.forEach { mode ->
            Surface(
                modifier = Modifier.weight(1f).clickable { onSelected(mode) },
                shape = RoundedCornerShape(10.dp),
                color = if (selected == mode) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(vertical = 10.dp)) {
                    Icon(mode.icon, null, tint = if (selected == mode) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(mode.label, style = MaterialTheme.typography.labelSmall, color = if (selected == mode) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun GroupHeader(title: String, onBack: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back") }
        Text(title, style = MaterialTheme.typography.titleLarge)
    }
}

@Composable
private fun GroupList(mode: BrowseMode, tracks: List<IpodTrack>, rootUri: Uri?, onGroup: (String) -> Unit) {
    val groups = mode.groups(tracks)
    val previews = if (mode == BrowseMode.Albums) {
        tracks.groupBy { it.album.ifEmpty { "Unknown Album" } }
            .mapValues { (_, albumTracks) -> albumTracks.first() }
    } else {
        emptyMap()
    }
    LazyColumn(contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        items(groups) { group ->
            Row(modifier = Modifier.fillMaxWidth().clickable { onGroup(group) }.padding(horizontal = 12.dp, vertical = 15.dp), verticalAlignment = Alignment.CenterVertically) {
                if (mode == BrowseMode.Albums) {
                    AlbumArtworkPreview(previews[group], rootUri)
                }
                Text(group, modifier = Modifier.weight(1f).padding(start = if (mode == BrowseMode.Albums) 12.dp else 0.dp), style = MaterialTheme.typography.bodyLarge)
                Text("›", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.titleLarge)
            }
        }
    }
}

@Composable
private fun AlbumArtworkPreview(track: IpodTrack?, rootUri: Uri?) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var artwork by remember(track) { mutableStateOf<Artwork?>(track?.artwork) }
    LaunchedEffect(track?.trackId, rootUri) {
        if (artwork == null && track != null && rootUri != null) {
            artwork = withContext(Dispatchers.IO) {
                IpodScanner.loadArtwork(context, rootUri, track)
            }
        }
    }
    ArtworkThumb(artwork, size = 52.dp)
}

@Composable
private fun TrackList(tracks: List<IpodTrack>, rootUri: Uri?, onTrack: (IpodTrack) -> Unit) {
    LazyColumn(contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        items(tracks, key = { it.path }) { track ->
            TrackRow(track, rootUri, onTrack)
        }
    }
}

@Composable
private fun TrackRow(track: IpodTrack, rootUri: Uri?, onTrack: (IpodTrack) -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var artwork by remember(track) { mutableStateOf<Artwork?>(track.artwork) }
    LaunchedEffect(track.trackId, rootUri) {
        if (artwork == null && rootUri != null) {
            artwork = withContext(Dispatchers.IO) {
                IpodScanner.loadArtwork(context, rootUri, track)
            }
        }
    }
    Row(modifier = Modifier.fillMaxWidth().clickable { onTrack(track) }.padding(horizontal = 8.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        ArtworkThumb(artwork)
                Column(modifier = Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Text(track.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(listOf(track.artist, track.album).filter { it.isNotEmpty() }.joinToString(" · ").ifEmpty { "MP3 track" }, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Text("›", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.titleLarge)
    }
}

@Composable
private fun ArtworkThumb(artwork: Artwork?, size: androidx.compose.ui.unit.Dp = 42.dp) {
    if (artwork == null) {
        Surface(modifier = Modifier.size(size), shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
            Icon(Icons.Default.MusicNote, null, modifier = Modifier.padding(10.dp).size(22.dp), tint = MaterialTheme.colorScheme.primary)
        }
        return
    }
    val bitmap = remember(artwork) {
        val colors = IntArray(artwork.width * artwork.height)
        for (index in colors.indices) {
            val offset = index * 3
            colors[index] = AndroidColor.rgb(artwork.rgb[offset].toInt() and 0xff,
                artwork.rgb[offset + 1].toInt() and 0xff, artwork.rgb[offset + 2].toInt() and 0xff)
        }
        Bitmap.createBitmap(colors, artwork.width, artwork.height, Bitmap.Config.RGB_565)
    }
    Image(bitmap.asImageBitmap(), "Album artwork", modifier = Modifier.size(size), contentScale = androidx.compose.ui.layout.ContentScale.Crop)
}

@Composable
private fun MiniPlayer(track: IpodTrack, rootUri: Uri?, isPlaying: Boolean, onOpen: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var artwork by remember(track) { mutableStateOf<Artwork?>(track.artwork) }
    LaunchedEffect(track.trackId, rootUri) {
        if (artwork == null && rootUri != null) {
            artwork = withContext(Dispatchers.IO) {
                IpodScanner.loadArtwork(context, rootUri, track)
            }
        }
    }
    Surface(modifier = Modifier.fillMaxWidth().clickable { onOpen() }, color = MaterialTheme.colorScheme.surfaceVariant) {
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            ArtworkThumb(artwork, size = 36.dp)
            Column(modifier = Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(track.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                Text(track.artist.ifEmpty { "Now playing from iPod" }, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            }
            Icon(if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, if (isPlaying) "Pause" else "Play", tint = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun NowPlayingScreen(modifier: Modifier, track: IpodTrack?, rootUri: Uri?, isPlaying: Boolean, onToggle: () -> Unit, onPrevious: () -> Unit, onNext: () -> Unit, onStop: () -> Unit, onBrowse: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var artwork by remember(track) { mutableStateOf<Artwork?>(track?.artwork) }
    LaunchedEffect(track?.trackId, rootUri) {
        if (artwork == null && track != null && rootUri != null) {
            artwork = withContext(Dispatchers.IO) {
                IpodScanner.loadArtwork(context, rootUri, track)
            }
        }
    }
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val landscape = maxWidth > maxHeight
        val artSize = if (landscape) (maxHeight * 0.6f).coerceIn(120.dp, 320.dp) else (maxHeight * 0.38f).coerceIn(120.dp, 270.dp)
        val artwork_ = @Composable {
            Surface(modifier = Modifier.size(artSize), shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                Box(contentAlignment = Alignment.Center) { ArtworkThumb(artwork, size = artSize) }
            }
        }
        val info = @Composable { horizontalAlignment: Alignment.Horizontal ->
            Column(horizontalAlignment = horizontalAlignment) {
                Text(track?.title ?: "Nothing playing", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = if (landscape) TextAlign.Start else TextAlign.Center)
                Text(track?.artist ?: "Choose a song from your library", color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = if (landscape) TextAlign.Start else TextAlign.Center)
            }
        }
        val controls = @Composable {
            if (track != null) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                    IconButton(onClick = onPrevious) { Icon(Icons.Default.SkipPrevious, "Previous") }
                    IconButton(onClick = onToggle, modifier = Modifier.size(64.dp).background(MaterialTheme.colorScheme.primary, RoundedCornerShape(32.dp))) {
                        Icon(if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(30.dp))
                    }
                    IconButton(onClick = onNext) { Icon(Icons.Default.SkipNext, "Next") }
                    IconButton(onClick = onStop) { Icon(Icons.Default.Stop, "Stop") }
                }
            } else {
                Button(onClick = onBrowse) { Text("Browse library") }
            }
        }
        if (landscape) {
            Row(
                modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(32.dp)
            ) {
                artwork_()
                Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.Start) {
                    Text("NOW PLAYING", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
                    Spacer(Modifier.height(12.dp))
                    info(Alignment.Start)
                    Spacer(Modifier.height(16.dp))
                    controls()
                }
            }
        } else {
            Column(
                modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("NOW PLAYING", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.height(12.dp))
                artwork_()
                Spacer(Modifier.height(16.dp))
                info(Alignment.CenterHorizontally)
                Spacer(Modifier.height(16.dp))
                controls()
            }
        }
    }

}

private const val LOG_PAGE = 20

@Composable
private fun SettingsScreen(modifier: Modifier, activityLog: List<String>, savedVolume: Boolean, onChooseVolume: () -> Unit, onClearVolume: () -> Unit, onShareLog: () -> Unit, onClearLog: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var visibleLines by remember { mutableStateOf(LOG_PAGE) }
    var confirmClear by remember { mutableStateOf(false) }
    LazyColumn(modifier = modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        item {
            Text("Settings", style = MaterialTheme.typography.headlineMedium)
            Text("PodFS device and diagnostics", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item { SettingSection("SOURCE", Icons.Default.Usb) {
            SettingRow("iPod volume", if (savedVolume) "Remembered" else "Not selected", Icons.Default.Storage)
            Button(onClick = onChooseVolume, modifier = Modifier.fillMaxWidth()) { Text("Choose another volume") }
            if (savedVolume) Text("The selected volume is scanned automatically when PodFS opens.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            if (savedVolume) Text("Forget remembered volume", color = MaterialTheme.colorScheme.primary, modifier = Modifier.clickable { onClearVolume }.padding(vertical = 8.dp))
        } }
        item { SettingSection("ABOUT", Icons.Default.Info) {
            SettingRow("PodFS", "iPod Classic music player", Icons.Default.Headphones)
            SettingRow("Version", "0.1 development build", Icons.Default.Info)
            SettingRow("Author", "Noe Rodriguez", Icons.Default.Person)
            Text(
                "github.com/noahrod",
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clickable {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/noahrod")))
                }
            )
            Text("Read-only playback for mounted sixth-generation iPod Classic volumes.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        } }
        item { SettingSection("DEBUG LOG", Icons.Default.BugReport) {
            Button(onClick = onShareLog, modifier = Modifier.fillMaxWidth()) { Text("Email debug log") }
            Button(onClick = { confirmClear = true }, modifier = Modifier.fillMaxWidth()) { Text("Delete debug log") }
            activityLog.asReversed().take(visibleLines).forEach { entry -> Text(entry, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 2.dp)) }
            if (visibleLines < activityLog.size) {
                Button(onClick = { visibleLines += LOG_PAGE }, modifier = Modifier.fillMaxWidth()) { Text("Load more") }
            }
        } }
    }
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Delete debug log?") },
            text = { Text("This removes all saved log entries from this device.") },
            confirmButton = {
                TextButton(onClick = { confirmClear = false; onClearLog() }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun SettingSection(title: String, icon: ImageVector, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(title, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
        }
        Surface(color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) { content() }
        }
    }
}

@Composable
private fun SettingRow(title: String, value: String, icon: ImageVector) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
        Column(modifier = Modifier.padding(start = 12.dp)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            Text(value, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        }
    }
}

private fun BrowseMode.groups(tracks: List<IpodTrack>): List<String> = when (this) {
    BrowseMode.Artists -> tracks.map { it.artist.ifEmpty { "Unknown Artist" } }
    BrowseMode.Albums -> tracks.map { it.album.ifEmpty { "Unknown Album" } }
    BrowseMode.Songs, BrowseMode.Playlists -> emptyList()
}.distinct().sortedWith(String.CASE_INSENSITIVE_ORDER)

// Playlist membership is resolved from iTunesDB playlists, not by group name.
private fun BrowseMode.matches(track: IpodTrack, group: String): Boolean = when (this) {
    BrowseMode.Artists -> track.artist.ifEmpty { "Unknown Artist" } == group
    BrowseMode.Albums -> track.album.ifEmpty { "Unknown Album" } == group
    BrowseMode.Songs, BrowseMode.Playlists -> true
}
