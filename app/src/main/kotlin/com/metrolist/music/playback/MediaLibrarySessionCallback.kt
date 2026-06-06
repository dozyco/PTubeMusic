/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.playback

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.Bundle
import androidx.annotation.DrawableRes
import androidx.core.net.toUri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.exoplayer.offline.Download
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSession.MediaItemsWithStartPosition
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.AlbumItem
import com.metrolist.innertube.models.ArtistItem
import com.metrolist.innertube.models.PlaylistItem
import com.metrolist.innertube.models.SongItem
import com.metrolist.innertube.models.filterExplicit
import com.metrolist.innertube.models.filterVideoSongs
import com.metrolist.innertube.utils.completed
import com.metrolist.music.R
import com.metrolist.music.constants.AndroidAutoSectionsOrderKey
import com.metrolist.music.constants.AndroidAutoYouTubePlaylistsKey
import com.metrolist.music.constants.HideExplicitKey
import com.metrolist.music.constants.HideVideoSongsKey
import com.metrolist.music.constants.MediaSessionConstants
import com.metrolist.music.db.MusicDatabase
import com.metrolist.music.db.entities.PlaylistEntity
import com.metrolist.music.db.entities.Song
import com.metrolist.music.extensions.toMediaItem
import com.metrolist.music.extensions.toggleRepeatMode
import com.metrolist.music.models.toMediaMetadata
import com.metrolist.music.ui.screens.settings.AndroidAutoSection
import com.metrolist.music.ui.screens.settings.serializeSections
import com.metrolist.music.utils.dataStore
import com.metrolist.music.utils.get
import com.metrolist.music.utils.getArtistSeparator
import com.metrolist.music.utils.joinToArtistString
import com.metrolist.music.utils.reportException
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.guava.future
import kotlinx.coroutines.plus
import javax.inject.Inject
import com.metrolist.music.automotive.AlbumArtContentProvider
import com.metrolist.music.automotive.LogBuffer
import kotlinx.coroutines.withTimeoutOrNull

class MediaLibrarySessionCallback
@Inject
constructor(
    @ApplicationContext val context: Context,
    val database: MusicDatabase,
    val downloadUtil: DownloadUtil,
) : MediaLibrarySession.Callback {
    private val scope = CoroutineScope(Dispatchers.Main) + Job()
    lateinit var service: MusicService
    var toggleLike: () -> Unit = {}
    var toggleStartRadio: () -> Unit = {}
    var toggleLibrary: () -> Unit = {}
    var addToTargetPlaylist: () -> Unit = {}

    @Volatile
    private var lastSearchSongs: List<SongItem> = emptyList()
    @Volatile
    private var lastRecommendedSongs: List<SongItem> = emptyList()
    private var lastHomeSections: List<com.metrolist.innertube.pages.HomePage.Section> = emptyList()

    fun release() {
        scope.cancel()
    }

    /**
     * SongItem 을 MediaItem 으로 변환.
     * 썸네일은 ArtworkProvider 의 content:// URI 를 사용한다.
     */
    private fun SongItem.toCarMediaItem(parentId: String): MediaItem {
        return MediaItem.Builder()
            .setMediaId("$parentId/$id")
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setSubtitle(artists.joinToString(", ") { it.name })
                    .setArtist(artists.joinToString(", ") { it.name })
                    .setArtworkUri(thumbnail?.toUri()?.let { AlbumArtContentProvider.mapUri(it) })
                    .setIsPlayable(true)
                    .setIsBrowsable(false)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                    .build()
            ).build()
    }

    /**
     * 브라우저블 MediaItem (Artists/Albums 폴더용) 을 만든다.
     * 썸네일은 ArtworkProvider 의 content:// URI 를 사용한다.
     */
    private fun browsableMediaItemWithArtwork(
        id: String,
        title: String,
        subtitle: String?,
        artworkUrl: String?,
        mediaType: Int = MediaMetadata.MEDIA_TYPE_MUSIC,
    ): MediaItem {
        return MediaItem.Builder()
            .setMediaId(id)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setSubtitle(subtitle)
                    .setArtist(subtitle)
                    .setArtworkUri(artworkUrl?.toUri()?.let { AlbumArtContentProvider.mapUri(it) })
                    .setIsPlayable(false)
                    .setIsBrowsable(true)
                    .setMediaType(mediaType)
                    .build()
            ).build()
    }

    override fun onConnect(
        session: MediaSession,
        controller: MediaSession.ControllerInfo,
    ): MediaSession.ConnectionResult {
        val connectionResult = super.onConnect(session, controller)
        return MediaSession.ConnectionResult.accept(
            connectionResult.availableSessionCommands
                .buildUpon()
                .add(MediaSessionConstants.CommandToggleLike)
                .add(MediaSessionConstants.CommandToggleStartRadio)
                .add(MediaSessionConstants.CommandToggleLibrary)
                .add(MediaSessionConstants.CommandToggleShuffle)
                .add(MediaSessionConstants.CommandToggleRepeatMode)
                .add(MediaSessionConstants.CommandAddToTargetPlaylist)
                .build(),
            connectionResult.availablePlayerCommands,
        )
    }

    override fun onCustomCommand(
        session: MediaSession,
        controller: MediaSession.ControllerInfo,
        customCommand: SessionCommand,
        args: Bundle,
    ): ListenableFuture<SessionResult> {
        when (customCommand.customAction) {
            MediaSessionConstants.ACTION_TOGGLE_LIKE -> toggleLike()
            MediaSessionConstants.ACTION_TOGGLE_START_RADIO -> toggleStartRadio()
            MediaSessionConstants.ACTION_TOGGLE_LIBRARY -> toggleLibrary()
            MediaSessionConstants.ACTION_TOGGLE_SHUFFLE -> session.player.shuffleModeEnabled =
                !session.player.shuffleModeEnabled

            MediaSessionConstants.ACTION_TOGGLE_REPEAT_MODE -> session.player.toggleRepeatMode()
            MediaSessionConstants.ACTION_ADD_TO_TARGET_PLAYLIST -> addToTargetPlaylist()
        }
        return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
    }

    @Deprecated("Deprecated in MediaLibrarySession.Callback")
    override fun onPlaybackResumption(
        mediaSession: MediaSession,
        controller: MediaSession.ControllerInfo
    ): ListenableFuture<MediaItemsWithStartPosition> {
        return SettableFuture.create<MediaItemsWithStartPosition>()
    }

    override fun onGetLibraryRoot(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        params: MediaLibraryService.LibraryParams?,
    ): ListenableFuture<LibraryResult<MediaItem>> {
        // 차량이 보내는 root hint 전체 로깅 (EXTRA_MEDIA_ART_SIZE_HINT_PIXELS 확인용)
        LogBuffer.log("onGetLibraryRoot 호출됨, controller=${browser.packageName}")
        val extras = params?.extras
        if (extras != null) {
            LogBuffer.log("rootHints 키 개수: ${extras.keySet().size}")
            extras.keySet().forEach { key ->
                LogBuffer.log("rootHint: $key = ${extras.get(key)}")
            }
        } else {
            LogBuffer.log("rootHints 없음 (params or extras null)")
        }
        // 전역 콘텐츠 스타일 힌트.
        // 폴스타4 등 일부 OEM 차량은 개별 MediaItem 의 CONTENT_STYLE_BROWSABLE_HINT 를 무시하고
        // 이 root(전역) 설정만 본다. 그래서 여기에 그리드 힌트를 박아야 큰 카드로 표시된다.
        val rootExtras = android.os.Bundle().apply {
            putInt("android.media.browse.CONTENT_STYLE_BROWSABLE_HINT", 2)  // 2 = 그리드
            putInt("android.media.browse.CONTENT_STYLE_PLAYABLE_HINT", 1)   // 1 = 리스트 (곡은 목록으로)
        }
        val mergedParams = MediaLibraryService.LibraryParams.Builder()
            .setExtras(
                (params?.extras ?: android.os.Bundle()).apply { putAll(rootExtras) }
            )
            .build()

        return Futures.immediateFuture(
            LibraryResult.ofItem(
                MediaItem
                    .Builder()
                    .setMediaId(MusicService.ROOT)
                    .setMediaMetadata(
                        MediaMetadata
                            .Builder()
                            .setIsPlayable(false)
                            .setIsBrowsable(false)
                            .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
                            .build(),
                    ).build(),
                mergedParams,
            ),
        )
    }

    override fun onGetChildren(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        parentId: String,
        page: Int,
        pageSize: Int,
        params: MediaLibraryService.LibraryParams?,
    ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> =
        scope.future(Dispatchers.IO) {
            LogBuffer.log("onGetChildren: parentId=$parentId")

            // 차량 시동 직후엔 player나 네트워크가 아직 준비 안 됐을 수 있다.
            // 최대 5초까지 짧은 대기 후 진행. 그래도 준비 안 되면 ofError 반환.
            val waitStart = System.currentTimeMillis()
            val maxWaitMs = 5000L
            val pollIntervalMs = 200L
            while (System.currentTimeMillis() - waitStart < maxWaitMs) {
                val playerReady = service.isPlayerReady.value
                val networkReady = service.isNetworkConnected.value
                if (playerReady && networkReady) break
                kotlinx.coroutines.delay(pollIntervalMs)
            }
            val waited = System.currentTimeMillis() - waitStart
            val finalPlayerReady = service.isPlayerReady.value
            val finalNetworkReady = service.isNetworkConnected.value
            LogBuffer.log("onGetChildren 준비 대기 종료: ${waited}ms, player=$finalPlayerReady, network=$finalNetworkReady")

            if (!finalPlayerReady || !finalNetworkReady) {
                LogBuffer.log("onGetChildren: 준비 안 됨 → ofError 반환 (parentId=$parentId)")
                return@future LibraryResult.ofError(SessionError.ERROR_SESSION_DISCONNECTED)
            }

            val items: List<MediaItem> = when (parentId) {
                MusicService.ROOT -> {
                    val sectionsRaw = context.dataStore.get(
                        AndroidAutoSectionsOrderKey,
                        serializeSections(AndroidAutoSection.values().map { it to true })
                    )
                    val sections = listOf(
                        AndroidAutoSection.HOME to true,  // Home (맨 앞으로)
                        AndroidAutoSection.LIKED to true,
                        AndroidAutoSection.ARTISTS to true,
                        AndroidAutoSection.PLAYLISTS to true,
                    )
                    val showYoutubePlaylists = context.dataStore.get(AndroidAutoYouTubePlaylistsKey, false)
                    val rootItems = sections
                        .filter { (_, enabled) -> enabled }
                        .ifEmpty { listOf(AndroidAutoSection.LIKED to true) }
                        .map { (section, _) ->
                            when (section) {
                                AndroidAutoSection.LIKED -> browsableMediaItem(
                                    "${MusicService.PLAYLIST}/${PlaylistEntity.LIKED_PLAYLIST_ID}",
                                    context.getString(R.string.liked_songs),
                                    null,
                                    drawableUri(R.drawable.favorite),
                                    MediaMetadata.MEDIA_TYPE_PLAYLIST,
                                )
                                AndroidAutoSection.SONGS -> browsableMediaItem(
                                    MusicService.SONG,
                                    context.getString(R.string.songs),
                                    null,
                                    drawableUri(R.drawable.music_note),
                                    MediaMetadata.MEDIA_TYPE_PLAYLIST,
                                )
                                AndroidAutoSection.ARTISTS -> browsableMediaItem(
                                    MusicService.ARTIST,
                                    context.getString(R.string.artists),
                                    null,
                                    drawableUri(R.drawable.artist),
                                    MediaMetadata.MEDIA_TYPE_FOLDER_ARTISTS,
                                )
                                AndroidAutoSection.ALBUMS -> browsableMediaItem(
                                    MusicService.ALBUM,
                                    context.getString(R.string.albums),
                                    null,
                                    drawableUri(R.drawable.album),
                                    MediaMetadata.MEDIA_TYPE_FOLDER_ALBUMS,
                                )
                                AndroidAutoSection.PLAYLISTS -> browsableMediaItem(
                                    MusicService.PLAYLIST,
                                    context.getString(R.string.playlists),
                                    null,
                                    drawableUri(R.drawable.queue_music),
                                    MediaMetadata.MEDIA_TYPE_FOLDER_PLAYLISTS,
                                )
                                AndroidAutoSection.HOME -> browsableMediaItem(
                                    MusicService.RECOMMENDED,
                                    context.getString(R.string.android_auto_recommended),
                                    null,
                                    drawableUri(R.drawable.home_filled),
                                    MediaMetadata.MEDIA_TYPE_FOLDER_MIXED,
                                )
                            }
                        }
                    LogBuffer.log("ROOT tabs count=${rootItems.size}, names=${rootItems.map { it.mediaMetadata.title }}")
                    if (showYoutubePlaylists) {
                        rootItems + browsableMediaItem(
                            MusicService.YOUTUBE_PLAYLIST,
                            context.getString(R.string.mixes),
                            null,
                            drawableUri(R.drawable.explore_outlined),
                            MediaMetadata.MEDIA_TYPE_FOLDER_PLAYLISTS,
                        )
                    } else {
                        rootItems
                    }
                }

                MusicService.SONG -> {
                    val libStart = System.currentTimeMillis()
                    LogBuffer.log("YouTube.library(FEmusic_liked_videos) 호출 시작 (SONG)")
                    val songs: List<SongItem> = try {
                        val result = withTimeoutOrNull(10_000) {
                            YouTube.library("FEmusic_liked_videos").completed().getOrNull()
                                ?.items?.filterIsInstance<SongItem>()
                                ?.filterExplicit(context.dataStore.get(HideExplicitKey, false))
                                ?.filterVideoSongs(context.dataStore.get(HideVideoSongsKey, false))
                                ?: emptyList()
                        } ?: run {
                            LogBuffer.log("YouTube.library(SONG) 타임아웃 (10초)")
                            emptyList()
                        }
                        LogBuffer.log("YouTube.library(FEmusic_liked_videos) 완료 (SONG), ${System.currentTimeMillis() - libStart}ms, songs=${result.size}")
                        result
                    } catch (e: Exception) {
                        LogBuffer.log("YouTube.library(FEmusic_liked_videos) 실패 (SONG), ${System.currentTimeMillis() - libStart}ms: ${e.message}")
                        reportException(e)
                        emptyList()
                    }

                    val shuffleItem: MediaItem = MediaItem.Builder()
                        .setMediaId("${MusicService.SONG}/${MusicService.SHUFFLE_ACTION}")
                        .setMediaMetadata(
                            MediaMetadata.Builder()
                                .setTitle(context.getString(R.string.shuffle))
                                .setArtworkUri(drawableUri(R.drawable.shuffle))
                                .setIsPlayable(true)
                                .setIsBrowsable(false)
                                .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                                .build()
                        ).build()

                    val songItems: List<MediaItem> = songs.map { it.toCarMediaItem(MusicService.SONG) }

                    listOf(shuffleItem) + songItems
                }

                MusicService.ARTIST -> {
                    val artistStart = System.currentTimeMillis()
                    LogBuffer.log("YouTube.library(corpus_artists) + libraryPodcastChannels 호출 시작")

                    // 1) 음악 아티스트 구독
                    val musicArtists: List<ArtistItem> = try {
                        withTimeoutOrNull(10_000) {
                            YouTube.library("FEmusic_library_corpus_artists").completed().getOrNull()
                                ?.items?.filterIsInstance<ArtistItem>()
                        }
                    } catch (e: Exception) {
                        LogBuffer.log("corpus_artists 실패: ${e.message}")
                        null
                    } ?: emptyList()

                    // 2) 일반 채널 구독 (음악 아닌 채널)
                    val otherChannels: List<ArtistItem> = try {
                        withTimeoutOrNull(10_000) {
                            YouTube.libraryPodcastChannels().getOrNull()
                                ?.items?.filterIsInstance<ArtistItem>()
                        }
                    } catch (e: Exception) {
                        LogBuffer.log("libraryPodcastChannels 실패: ${e.message}")
                        null
                    } ?: emptyList()

                    // 3) 합치고 중복 제거 (id 기준)
                    val artistList: List<ArtistItem> =
                        (musicArtists + otherChannels).distinctBy { it.id }

                    LogBuffer.log("ARTIST 음악=${musicArtists.size}, 일반채널=${otherChannels.size}, 합계=${artistList.size}")
                    LogBuffer.log("ARTIST count=${artistList.size}, first=${artistList.firstOrNull()?.title}")

                    artistList.map { artist ->
                        // 그리드 형식 hint 박아서 큰 카드로 표시 (공식 YouTube Music 처럼)
                        // 추가로 ALBUM_ART_URI/ART_URI/DISPLAY_ICON_URI 3개 키에 직접 박아 차량이 고화질 처리하게 함
                        val artworkUri = artist.thumbnail?.let { url ->
                            val hiRes = url
                                .replace(Regex("=w\\d+-h\\d+(-[^=&]*)?"), "=w1080-h1080-l90-rj")
                                .replace(Regex("=s\\d+(-[^=&]*)?"), "=s1080-l90-rj")
                            hiRes.toUri()
                        }?.let { AlbumArtContentProvider.mapUriCrop(it) }

                        val extras = android.os.Bundle().apply {
                            putInt("android.media.browse.CONTENT_STYLE_BROWSABLE_HINT", 2)
                            putInt("android.media.browse.CONTENT_STYLE_SINGLE_ITEM_HINT", 2)
                            // 3가지 메타데이터 키에 똑같이 박기 (코덱스 해결책 핵심)
                            if (artworkUri != null) {
                                val uriString = artworkUri.toString()
                                putString("android.media.metadata.DISPLAY_ICON_URI", uriString)
                                putString("android.media.metadata.ALBUM_ART_URI", uriString)
                                putString("android.media.metadata.ART_URI", uriString)
                            }
                        }

                        MediaItem.Builder()
                            .setMediaId("${MusicService.ARTIST}/${artist.id}")
                            .setMediaMetadata(
                                MediaMetadata.Builder()
                                    .setTitle(artist.title)
                                    .setSubtitle("Artist")
                                    .setArtist("Artist")
                                    .setArtworkUri(artworkUri)
                                    .setIsPlayable(false)
                                    .setIsBrowsable(true)
                                    .setMediaType(MediaMetadata.MEDIA_TYPE_ARTIST)
                                    .setExtras(extras)
                                    .build()
                            ).build()
                    }
                }

                MusicService.ALBUM -> {
                    val albumList: List<AlbumItem> = try {
                        withTimeoutOrNull(10_000) {
                            YouTube.library("FEmusic_liked_albums").completed().getOrNull()
                                ?.items?.filterIsInstance<AlbumItem>()
                                ?: emptyList()
                        } ?: run {
                            LogBuffer.log("YouTube.library(ALBUM) 타임아웃 (10초)")
                            emptyList()
                        }
                    } catch (e: Exception) {
                        reportException(e)
                        emptyList()
                    }

                    albumList.map { album ->
                        browsableMediaItemWithArtwork(
                            "${MusicService.ALBUM}/${album.id}",
                            album.title,
                            album.artists?.joinToString(", ") { it.name },
                            album.thumbnail,
                            MediaMetadata.MEDIA_TYPE_ALBUM,
                        )
                    }
                }

                MusicService.PLAYLIST -> {
                    // YouTube 라이브러리에 저장/생성한 실제 플레이리스트만 표시
                    val ytPlaylistStart = System.currentTimeMillis()
                    LogBuffer.log("YouTube.library(FEmusic_liked_playlists) 호출 시작")
                    val ytPlaylists: List<PlaylistItem> = try {
                        val result = withTimeoutOrNull(10_000) {
                            YouTube.library("FEmusic_liked_playlists").completed().getOrNull()
                                ?.items?.filterIsInstance<PlaylistItem>()
                                ?: emptyList()
                        } ?: run {
                            LogBuffer.log("YouTube.library(PLAYLIST) 타임아웃 (10초)")
                            emptyList()
                        }
                        LogBuffer.log("YouTube.library(FEmusic_liked_playlists) 완료, ${System.currentTimeMillis() - ytPlaylistStart}ms, playlists=${result.size}")
                        result
                    } catch (e: Exception) {
                        LogBuffer.log("YouTube.library(FEmusic_liked_playlists) 실패: ${e.message}")
                        reportException(e)
                        emptyList()
                    }

                    ytPlaylists
                        // 자동 플레이리스트(좋아요 음악 LM, 나중에 들을 에피소드 SE) 제외
                        .filterNot { it.id == "LM" || it.id == "SE" || it.id == "VLLM" || it.id == "VLSE" }
                        .map { playlist ->
                            // YouTube 플레이리스트는 YOUTUBE_PLAYLIST 경로로 (클릭 시 YouTube.playlist() 로 곡 로드)
                            browsableMediaItemWithArtwork(
                                "${MusicService.YOUTUBE_PLAYLIST}/${playlist.id}",
                                playlist.title,
                                playlist.author?.name,
                                playlist.thumbnail,
                                MediaMetadata.MEDIA_TYPE_PLAYLIST,
                            )
                        }
                }

                MusicService.YOUTUBE_PLAYLIST -> {
                    if (!context.dataStore.get(AndroidAutoYouTubePlaylistsKey, false)) {
                        emptyList()
                    } else {
                        try {
                            val allSections = mutableListOf<com.metrolist.innertube.pages.HomePage.Section>()
                            var continuation: String? = null
                            val maxPages = 4

                            for (page in 0 until maxPages) {
                                val result = YouTube.home(continuation)
                                    .onFailure { reportException(it) }
                                    .getOrNull() ?: break
                                allSections.addAll(result.sections)
                                continuation = result.continuation
                                if (continuation == null) break
                            }

                            val savedBrowseIds = database.playlistsByCreateDateAsc()
                                .first()
                                .mapNotNullTo(mutableSetOf()) { it.playlist.browseId }

                            val playlists = allSections
                                .flatMap { it.items }
                                .filterIsInstance<PlaylistItem>()
                                .filterNot { it.id in savedBrowseIds }
                                .distinctBy { it.id }

                            playlists.map { playlist ->
                                browsableMediaItemWithArtwork(
                                    "${MusicService.YOUTUBE_PLAYLIST}/${playlist.id}",
                                    playlist.title,
                                    playlist.author?.name,
                                    playlist.thumbnail,
                                    MediaMetadata.MEDIA_TYPE_PLAYLIST,
                                )
                            }

                        } catch (e: Exception) {
                            reportException(e)
                            emptyList()
                        }
                    }
                }

                MusicService.RECOMMENDED -> {
                    try {
                        val allSections = mutableListOf<com.metrolist.innertube.pages.HomePage.Section>()
                        var continuation: String? = null
                        val maxPages = 4

                        for (page in 0 until maxPages) {
                            val homeStart = System.currentTimeMillis()
                            LogBuffer.log("YouTube.home() 호출 시작 (HOME, page=$page)")
                            val result = withTimeoutOrNull(10_000) {
                                YouTube.home(continuation)
                                    .onFailure { LogBuffer.log("YouTube.home() 실패 (HOME, page=$page): ${it.message}"); reportException(it) }
                                    .getOrNull()
                            } ?: run {
                                LogBuffer.log("YouTube.home() 타임아웃 (HOME, page=$page, 10초)")
                                null
                            }
                            LogBuffer.log("YouTube.home() 완료 (HOME, page=$page), ${System.currentTimeMillis() - homeStart}ms, sections=${result?.sections?.size ?: -1}")
                            if (result == null) break
                            allSections.addAll(result.sections)
                            continuation = result.continuation
                            if (continuation == null) break
                        }

                        val hideExplicit = context.dataStore.get(HideExplicitKey, false)
                        val hideVideoSongs = context.dataStore.get(HideVideoSongsKey, false)

                        // 재생용 곡 캐싱 (RECOMMENDED/songId 분기에서 사용)
                        val allHomeSongs = allSections
                            .flatMap { it.items }
                            .filterIsInstance<SongItem>()
                            .filterExplicit(hideExplicit)
                            .filterVideoSongs(hideVideoSongs)
                            .distinctBy { it.id }
                        lastRecommendedSongs = allHomeSongs
                        lastHomeSections = allSections   // ← 폴더 클릭 시 재호출 없이 쓰려고 캐싱

                        // 홈 탭 = 섹션마다 폴더 하나. 폴더를 누르면 그 섹션 내용이 나오는 2단 구조.
                        val items = mutableListOf<MediaItem>()
                        val seenTitles = mutableSetOf<String>()

                        allSections.forEach { section ->
                            val title = section.title
                            if (title.isNullOrBlank()) return@forEach
                            if (!seenTitles.add(title)) return@forEach

                            val firstThumb = section.items.firstOrNull()?.let { item ->
                                when (item) {
                                    is SongItem -> item.thumbnail
                                    is PlaylistItem -> item.thumbnail
                                    is AlbumItem -> item.thumbnail
                                    is ArtistItem -> item.thumbnail
                                    else -> null
                                }
                            }

                            val encodedTitle = java.net.URLEncoder.encode(title, "UTF-8")
                            val folderExtras = android.os.Bundle().apply {
                                putInt("android.media.browse.CONTENT_STYLE_BROWSABLE_HINT", 2)  // 2 = 그리드(큰 카드)
                                putInt("android.media.browse.CONTENT_STYLE_SINGLE_ITEM_HINT", 2)
                            }
                            val folder = MediaItem.Builder()
                                .setMediaId("${MusicService.RECOMMENDED}/HOME_SECTION/$encodedTitle")
                                .setMediaMetadata(
                                    MediaMetadata.Builder()
                                        .setTitle(title)
                                        .setArtworkUri(firstThumb?.toUri()?.let { AlbumArtContentProvider.mapUri(it) })
                                        .setIsPlayable(false)
                                        .setIsBrowsable(true)
                                        .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
                                        .setExtras(folderExtras)
                                        .build()
                                ).build()
                            items.add(folder)
                        }

                        LogBuffer.log("HOME 폴더 ${items.size}개 생성")
                        items
                    } catch (e: Exception) {
                        reportException(e)
                        emptyList()
                    }
                }

                else -> {
                    when {
                        parentId.startsWith("${MusicService.ARTIST}/") -> {
                            val artistId = parentId.removePrefix("${MusicService.ARTIST}/")
                            val artistFetchStart = System.currentTimeMillis()
                            LogBuffer.log("YouTube.artist($artistId) 호출 시작")
                            val songs: List<SongItem> = try {
                                val artistPage = withTimeoutOrNull(10_000) {
                                    YouTube.artist(artistId).getOrNull()
                                } ?: run {
                                    LogBuffer.log("YouTube.artist() 타임아웃 (10초)")
                                    null
                                }
                                LogBuffer.log("YouTube.artist() 완료, ${System.currentTimeMillis() - artistFetchStart}ms, 섹션 수=${artistPage?.sections?.size ?: -1}")

                                // 1단계: 첫 페이지에서 모든 SongItem 모으기
                                val firstPageSongs = artistPage?.sections
                                    ?.flatMap { it.items }
                                    ?.filterIsInstance<SongItem>()
                                    ?: emptyList()
                                LogBuffer.log("아티스트 첫 페이지 곡=${firstPageSongs.size}")

                                // 2단계: Songs 섹션의 "더보기" endpoint 찾아서 추가 페이지 받기
                                val songsSectionEndpoint = artistPage?.sections
                                    ?.firstOrNull { section ->
                                        section.items.any { it is SongItem } && section.moreEndpoint != null
                                    }
                                    ?.moreEndpoint

                                val moreSongs = mutableListOf<SongItem>()
                                if (songsSectionEndpoint != null) {
                                    try {
                                        val moreStart = System.currentTimeMillis()
                                        LogBuffer.log("YouTube.artistItems() 호출 시작 (더보기)")
                                        val firstMorePage = YouTube.artistItems(songsSectionEndpoint).getOrNull()
                                        LogBuffer.log("YouTube.artistItems() 완료, ${System.currentTimeMillis() - moreStart}ms, items=${firstMorePage?.items?.size ?: -1}")

                                        firstMorePage?.items?.filterIsInstance<SongItem>()?.let { moreSongs.addAll(it) }

                                        // continuation 이 있으면 한 페이지 더 (최대 2페이지로 제한)
                                        var continuation = firstMorePage?.continuation
                                        var pageCount = 0
                                        val maxContinuationPages = 2
                                        while (continuation != null && pageCount < maxContinuationPages) {
                                            val contStart = System.currentTimeMillis()
                                            LogBuffer.log("YouTube.artistItemsContinuation() 호출 시작 (page=${pageCount + 1})")
                                            val contPage = YouTube.artistItemsContinuation(continuation).getOrNull()
                                            LogBuffer.log("YouTube.artistItemsContinuation() 완료, ${System.currentTimeMillis() - contStart}ms, items=${contPage?.items?.size ?: -1}")
                                            if (contPage == null) break
                                            contPage.items.filterIsInstance<SongItem>().let { moreSongs.addAll(it) }
                                            continuation = contPage.continuation
                                            pageCount++
                                        }
                                    } catch (e: Exception) {
                                        LogBuffer.log("artistItems 호출 실패: ${e.message}")
                                        reportException(e)
                                    }
                                } else {
                                    LogBuffer.log("Songs 섹션의 moreEndpoint 없음 - 추가 곡 불러오기 생략")
                                }

                                // 3단계: 합치고 중복 제거, 필터 적용
                                (firstPageSongs + moreSongs)
                                    .distinctBy { it.id }
                                    .filterExplicit(context.dataStore.get(HideExplicitKey, false))
                                    .filterVideoSongs(context.dataStore.get(HideVideoSongsKey, false))
                            } catch (e: Exception) {
                                LogBuffer.log("ARTIST 분기 전체 실패: ${e.message}")
                                reportException(e)
                                emptyList()
                            }

                            LogBuffer.log("아티스트($artistId) 곡 개수: ${songs.size}, 첫 곡=${songs.firstOrNull()?.title}")

                            val shuffleItem: MediaItem = MediaItem.Builder()
                                .setMediaId("$parentId/${MusicService.SHUFFLE_ACTION}")
                                .setMediaMetadata(
                                    MediaMetadata.Builder()
                                        .setTitle(context.getString(R.string.shuffle))
                                        .setArtworkUri(drawableUri(R.drawable.shuffle))
                                        .setIsPlayable(true)
                                        .setIsBrowsable(false)
                                        .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                                        .build()
                                ).build()

                            val songItems: List<MediaItem> = songs.map { it.toCarMediaItem(parentId) }

                            listOf(shuffleItem) + songItems
                        }

                        parentId.startsWith("${MusicService.ALBUM}/") -> {
                            val albumId = parentId.removePrefix("${MusicService.ALBUM}/")
                            val songs: List<SongItem> = try {
                                withTimeoutOrNull(10_000) {
                                    YouTube.album(albumId).getOrNull()?.songs
                                        ?.filterExplicit(context.dataStore.get(HideExplicitKey, false))
                                        ?.filterVideoSongs(context.dataStore.get(HideVideoSongsKey, false))
                                        ?: emptyList()
                                } ?: run {
                                    LogBuffer.log("YouTube.album() 타임아웃 (10초)")
                                    emptyList()
                                }
                            } catch (e: Exception) {
                                reportException(e)
                                emptyList()
                            }

                            val shuffleItem: MediaItem = MediaItem.Builder()
                                .setMediaId("$parentId/${MusicService.SHUFFLE_ACTION}")
                                .setMediaMetadata(
                                    MediaMetadata.Builder()
                                        .setTitle(context.getString(R.string.shuffle))
                                        .setArtworkUri(drawableUri(R.drawable.shuffle))
                                        .setIsPlayable(true)
                                        .setIsBrowsable(false)
                                        .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                                        .build()
                                ).build()

                            val songItems: List<MediaItem> = songs.map { it.toCarMediaItem(parentId) }

                            listOf(shuffleItem) + songItems
                        }

                        parentId.startsWith("${MusicService.PLAYLIST}/") -> {
                            val playlistId = parentId.removePrefix("${MusicService.PLAYLIST}/")

                            if (playlistId == PlaylistEntity.LIKED_PLAYLIST_ID) {
                                val songs: List<SongItem> = try {
                                    val lmStart = System.currentTimeMillis()
                                    val lmSongs = withTimeoutOrNull(10_000) {
                                        YouTube.playlist("LM").completed().getOrNull()
                                            ?.songs
                                            ?: emptyList()
                                    } ?: run {
                                        LogBuffer.log("YouTube.playlist('LM') 타임아웃 (10초)")
                                        emptyList()
                                    }
                                    LogBuffer.log("LIKED: playlist('LM').completed() → ${lmSongs.size}개, ${System.currentTimeMillis() - lmStart}ms")

                                    val afterExplicit = lmSongs.filterExplicit(context.dataStore.get(HideExplicitKey, false))
                                    val afterVideoSongs = afterExplicit.filterVideoSongs(context.dataStore.get(HideVideoSongsKey, false))
                                    LogBuffer.log("LIKED: 필터 후 ${afterVideoSongs.size}개")

                                    afterVideoSongs
                                } catch (e: Exception) {
                                    reportException(e)
                                    LogBuffer.log("LIKED 가져오기 실패: ${e.message}")
                                    emptyList()
                                }

                                // 동기화 1단계(서버 LM 을 무조건 liked=true 로 되살리던 코드)는 제거됨.
                                // 화면 표시: 서버 LM 목록을 그대로 보여주되, 차에서 명시적으로 해제한 곡만 제외.
                                //
                                // 중요: 필터를 insert 보다 "먼저" 실행한다.
                                // SongItem.toMediaMetadata() 는 liked 기본값이 false 라서,
                                // insert 를 먼저 하면 새 곡이 전부 DB 에 liked=false 로 들어가고
                                // 필터가 그걸 다 걸러내 버린다(재설치 후 DB 가 비면 좋아요 탭이 통째로 사라짐).
                                // 그래서 "지금 DB 에 이미 liked=false 로 저장돼 있는 곡"만 제외하고,
                                // 그 판단이 끝난 뒤에 insert 한다.
                                val explicitlyUnliked = try {
                                    songs.mapNotNull { songItem ->
                                        val dbSong = try {
                                            database.song(songItem.id).first()
                                        } catch (e: Exception) {
                                            null
                                        }
                                        // DB 에 곡이 존재하고 + liked 가 명시적으로 false 인 곡만 "해제됨" 으로 본다.
                                        if (dbSong != null && dbSong.song.liked == false) songItem.id else null
                                    }.toSet()
                                } catch (e: Exception) {
                                    LogBuffer.log("LIKED 해제곡 판별 실패: ${e.message}")
                                    emptySet()
                                }

                                val filteredSongs = songs
                                    .distinctBy { it.id }                 // 서버 LM 중복곡 화면 중복 방지
                                    .filterNot { it.id in explicitlyUnliked }  // 차에서 해제한 곡만 제외
                                LogBuffer.log("LIKED: 해제곡 ${explicitlyUnliked.size}개 제외 후 ${filteredSongs.size}개 (원본 ${songs.size}개)")

                                // 표시 판단이 끝났으니 이제 새 곡을 DB 에 insert (없는 곡만).
                                // 중요: LIKED 탭의 곡은 서버 LM 에 있는 = 좋아요된 곡이므로 liked=true 로 넣는다.
                                // (toMediaMetadata() 는 liked 기본값이 false 라서, 그냥 insert 하면
                                //  새 곡이 전부 false 로 저장되고 → 다음 진입 때 우리 필터가 그걸
                                //  "해제곡"으로 오판해 다 걸러버린다. 그래서 명시적으로 true 로 저장.)
                                // 기존에 DB 에 있던 곡은 절대 안 건드림(차에서 해제한 곡 보존).
                                try {
                                    songs.forEach { songItem ->
                                        try {
                                            val existing = database.song(songItem.id).first()
                                            if (existing == null) {
                                                val metadata = songItem.toMediaMetadata()
                                                database.query {
                                                    insert(metadata.copy(liked = true))
                                                }
                                            }
                                        } catch (e: Exception) {
                                            LogBuffer.log("LIKED insert 실패: id=${songItem.id}, ${e.message}")
                                        }
                                    }
                                } catch (e: Exception) {
                                    LogBuffer.log("LIKED insert 전체 실패: ${e.message}")
                                }

                                val shuffleItem: MediaItem = MediaItem.Builder()
                                    .setMediaId("$parentId/${MusicService.SHUFFLE_ACTION}")
                                    .setMediaMetadata(
                                        MediaMetadata.Builder()
                                            .setTitle(context.getString(R.string.shuffle))
                                            .setArtworkUri(drawableUri(R.drawable.shuffle))
                                            .setIsPlayable(true)
                                            .setIsBrowsable(false)
                                            .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                                            .build()
                                    ).build()

                                val songItems: List<MediaItem> = filteredSongs.map { it.toCarMediaItem(parentId) }

                                listOf(shuffleItem) + songItems
                            } else {
                                val songs: List<Song> = when (playlistId) {
                                    PlaylistEntity.DOWNLOADED_PLAYLIST_ID -> {
                                        val downloads = downloadUtil.downloads.value
                                        database
                                            .allSongs()
                                            .flowOn(Dispatchers.IO)
                                            .map { allSongs ->
                                                allSongs.filter {
                                                    downloads[it.id]?.state == Download.STATE_COMPLETED
                                                }
                                            }.map { filteredSongs ->
                                                filteredSongs
                                                    .map { it to downloads[it.id] }
                                                    .sortedBy { it.second?.updateTimeMs ?: 0L }
                                                    .map { it.first }
                                            }.first()
                                    }
                                    else -> database.playlistSongs(playlistId).map { list ->
                                        list.map { it.song }
                                    }.first()
                                }

                                val shuffleItem: MediaItem = MediaItem.Builder()
                                    .setMediaId("$parentId/${MusicService.SHUFFLE_ACTION}")
                                    .setMediaMetadata(
                                        MediaMetadata.Builder()
                                            .setTitle(context.getString(R.string.shuffle))
                                            .setArtworkUri(drawableUri(R.drawable.shuffle))
                                            .setIsPlayable(true)
                                            .setIsBrowsable(false)
                                            .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                                            .build()
                                    ).build()

                                listOf(shuffleItem) + songs.map { it.toMediaItem(parentId) }
                            }
                        }

                        parentId.startsWith("${MusicService.YOUTUBE_PLAYLIST}/") -> {
                            val playlistId = parentId.removePrefix("${MusicService.YOUTUBE_PLAYLIST}/")
                            val songs: List<SongItem> = try {
                                withTimeoutOrNull(10_000) {
                                    YouTube.playlist(playlistId).getOrNull()?.songs
                                        ?.take(100)
                                        ?.filterExplicit(context.dataStore.get(HideExplicitKey, false))
                                        ?.filterVideoSongs(context.dataStore.get(HideVideoSongsKey, false))
                                        ?: emptyList()
                                } ?: run {
                                    LogBuffer.log("YouTube.playlist() 타임아웃 (10초)")
                                    emptyList()
                                }
                            } catch (e: Exception) {
                                reportException(e)
                                emptyList()
                            }

                            val shuffleItem: MediaItem = MediaItem.Builder()
                                .setMediaId("$parentId/${MusicService.SHUFFLE_ACTION}")
                                .setMediaMetadata(
                                    MediaMetadata.Builder()
                                        .setTitle(context.getString(R.string.shuffle))
                                        .setArtworkUri(drawableUri(R.drawable.shuffle))
                                        .setIsPlayable(true)
                                        .setIsBrowsable(false)
                                        .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                                        .build()
                                ).build()

                            val songItems: List<MediaItem> = songs.map { it.toCarMediaItem(parentId) }

                            listOf(shuffleItem) + songItems
                        }

                        parentId.startsWith("${MusicService.RECOMMENDED}/HOME_SECTION/") -> {
                            val encoded = parentId.removePrefix("${MusicService.RECOMMENDED}/HOME_SECTION/")
                            val targetTitle = try { java.net.URLDecoder.decode(encoded, "UTF-8") } catch (e: Exception) { encoded }
                            LogBuffer.log("HOME_SECTION 열기: '$targetTitle'")

                            // 홈 탭에서 캐싱해둔 섹션 우선 사용 (재호출 없이 즉시 열림)
                            var sections = lastHomeSections
                            if (sections.isEmpty()) {
                                LogBuffer.log("HOME_SECTION: 캐시 비어있음, home 재호출")
                                val fetched = mutableListOf<com.metrolist.innertube.pages.HomePage.Section>()
                                var cont: String? = null
                                for (page in 0 until 4) {
                                    val result = withTimeoutOrNull(10_000) {
                                        YouTube.home(cont).getOrNull()
                                    } ?: break
                                    fetched.addAll(result.sections)
                                    cont = result.continuation
                                    if (cont == null) break
                                }
                                sections = fetched
                                lastHomeSections = fetched
                                // 재생용 곡 캐시도 같이 채운다 (이게 없으면 폴더 안 곡 재생이 안 됨)
                                if (lastRecommendedSongs.isEmpty()) {
                                    lastRecommendedSongs = fetched
                                        .flatMap { it.items }
                                        .filterIsInstance<SongItem>()
                                        .filterExplicit(context.dataStore.get(HideExplicitKey, false))
                                        .filterVideoSongs(context.dataStore.get(HideVideoSongsKey, false))
                                        .distinctBy { it.id }
                                }
                            }

                            val target = sections.firstOrNull { it.title == targetTitle }
                            if (target == null) {
                                LogBuffer.log("HOME_SECTION '$targetTitle' 못 찾음")
                                emptyList()
                            } else {
                                val result = mutableListOf<MediaItem>()
                                target.items.forEach { ytItem ->
                                    val mi: MediaItem? = when (ytItem) {
                                        is SongItem -> MediaItem.Builder()
                                            .setMediaId("${MusicService.RECOMMENDED}/${ytItem.id}")
                                            .setMediaMetadata(
                                                MediaMetadata.Builder()
                                                    .setTitle(ytItem.title)
                                                    .setSubtitle(ytItem.artists.joinToString(", ") { it.name })
                                                    .setArtist(ytItem.artists.joinToString(", ") { it.name })
                                                    .setArtworkUri(ytItem.thumbnail?.toUri()?.let { AlbumArtContentProvider.mapUri(it) })
                                                    .setIsPlayable(true)
                                                    .setIsBrowsable(false)
                                                    .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                                                    .build()
                                            ).build()
                                        is PlaylistItem ->
                                            if (ytItem.id == "LM" || ytItem.id == "SE" || ytItem.id == "VLLM" || ytItem.id == "VLSE") null
                                            else MediaItem.Builder()
                                                .setMediaId("${MusicService.YOUTUBE_PLAYLIST}/${ytItem.id}")
                                                .setMediaMetadata(
                                                    MediaMetadata.Builder()
                                                        .setTitle(ytItem.title)
                                                        .setSubtitle(ytItem.author?.name)
                                                        .setArtist(ytItem.author?.name)
                                                        .setArtworkUri(ytItem.thumbnail?.toUri()?.let { AlbumArtContentProvider.mapUri(it) })
                                                        .setIsPlayable(false)
                                                        .setIsBrowsable(true)
                                                        .setMediaType(MediaMetadata.MEDIA_TYPE_PLAYLIST)
                                                        .build()
                                                ).build()
                                        is AlbumItem -> MediaItem.Builder()
                                            .setMediaId("${MusicService.ALBUM}/${ytItem.browseId}")
                                            .setMediaMetadata(
                                                MediaMetadata.Builder()
                                                    .setTitle(ytItem.title)
                                                    .setSubtitle(ytItem.artists?.joinToString(", ") { it.name })
                                                    .setArtist(ytItem.artists?.joinToString(", ") { it.name })
                                                    .setArtworkUri(ytItem.thumbnail?.toUri()?.let { AlbumArtContentProvider.mapUri(it) })
                                                    .setIsPlayable(false)
                                                    .setIsBrowsable(true)
                                                    .setMediaType(MediaMetadata.MEDIA_TYPE_ALBUM)
                                                    .build()
                                            ).build()
                                        is ArtistItem -> MediaItem.Builder()
                                            .setMediaId("${MusicService.ARTIST}/${ytItem.id}")
                                            .setMediaMetadata(
                                                MediaMetadata.Builder()
                                                    .setTitle(ytItem.title)
                                                    .setSubtitle("Artist")
                                                    .setArtist("Artist")
                                                    .setArtworkUri(ytItem.thumbnail?.toUri()?.let { AlbumArtContentProvider.mapUri(it) })
                                                    .setIsPlayable(false)
                                                    .setIsBrowsable(true)
                                                    .setMediaType(MediaMetadata.MEDIA_TYPE_ARTIST)
                                                    .build()
                                            ).build()
                                        else -> null
                                    }
                                    if (mi != null) result.add(mi)
                                }
                                LogBuffer.log("HOME_SECTION '$targetTitle' 아이템 ${result.size}개")
                                result
                            }
                        }

                        parentId.startsWith("${MusicService.RECOMMENDED}/") -> {
                            try {
                                val allSections = mutableListOf<com.metrolist.innertube.pages.HomePage.Section>()
                                var continuation: String? = null
                                val maxPages = 4

                                for (page in 0 until maxPages) {
                                    val result = YouTube.home(continuation)
                                        .onFailure { reportException(it) }
                                        .getOrNull() ?: break
                                    allSections.addAll(result.sections)
                                    continuation = result.continuation
                                    if (continuation == null) break
                                }

                                val songs = allSections
                                    .flatMap { it.items }
                                    .filterIsInstance<SongItem>()
                                    .filterExplicit(context.dataStore.get(HideExplicitKey, false))
                                    .filterVideoSongs(context.dataStore.get(HideVideoSongsKey, false))
                                    .distinctBy { it.id }

                                val shuffleItem: MediaItem = MediaItem.Builder()
                                    .setMediaId("${MusicService.RECOMMENDED}/${MusicService.SHUFFLE_ACTION}")
                                    .setMediaMetadata(
                                        MediaMetadata.Builder()
                                            .setTitle(context.getString(R.string.shuffle))
                                            .setArtworkUri(drawableUri(R.drawable.shuffle))
                                            .setIsPlayable(true)
                                            .setIsBrowsable(false)
                                            .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                                            .build()
                                    ).build()

                                listOf(shuffleItem) + songs.map { it.toCarMediaItem(MusicService.RECOMMENDED) }
                            } catch (e: Exception) {
                                reportException(e)
                                emptyList()
                            }
                        }

                        else -> emptyList()
                    }
                }
            }

            LibraryResult.ofItemList(items, params)
        }

    override fun onGetItem(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        mediaId: String,
    ): ListenableFuture<LibraryResult<MediaItem>> =
        scope.future(Dispatchers.IO) {
            try {
                database.song(mediaId).first()?.toMediaItem()?.let {
                    LibraryResult.ofItem(it, null)
                } ?: LibraryResult.ofError(SessionError.ERROR_UNKNOWN)
            } catch (e: Exception) {
                reportException(e)
                LibraryResult.ofError(SessionError.ERROR_UNKNOWN)
            }
        }

    override fun onSearch(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        query: String,
        params: MediaLibraryService.LibraryParams?
    ): ListenableFuture<LibraryResult<Void>> {
        scope.future(Dispatchers.IO) {
            try {
                LogBuffer.log("onSearch 호출됨: query=$query")
                val onlineResults = YouTube.search(query, YouTube.SearchFilter.FILTER_SONG)
                    .getOrNull()
                    ?.items
                    ?.filterIsInstance<SongItem>()
                    ?: emptyList()
                LogBuffer.log("onSearch: ${onlineResults.size}개 결과")
                session.notifySearchResultChanged(browser, query, onlineResults.size, params)
            } catch (e: Exception) {
                LogBuffer.log("onSearch 실패: ${e.message}")
                session.notifySearchResultChanged(browser, query, 0, params)
            }
        }
        return Futures.immediateFuture(LibraryResult.ofVoid())
    }

    override fun onGetSearchResult(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        query: String,
        page: Int,
        pageSize: Int,
        params: MediaLibraryService.LibraryParams?
    ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
        return scope.future(Dispatchers.IO) {
            if (query.isEmpty()) {
                return@future LibraryResult.ofItemList(emptyList<MediaItem>(), params)
            }

            try {
                LogBuffer.log("onGetSearchResult: query=$query")
                val searchStart = System.currentTimeMillis()

                // 1. searchSummary 로 Top result 받기
                val summaryPage = YouTube.searchSummary(query).getOrNull()
                val topResultItems = summaryPage?.summaries
                    ?.firstOrNull { it.title.contains("Top", ignoreCase = true) || it.title.contains("최상", ignoreCase = true) }
                    ?.items
                    ?: emptyList()
                LogBuffer.log("Top result: ${topResultItems.size}개")

                // 2. 카테고리별 검색 병렬 호출 (각 필터로)
                val songsResult = YouTube.search(query, YouTube.SearchFilter.FILTER_SONG).getOrNull()
                    ?.items?.filterIsInstance<SongItem>()
                    ?.filterExplicit(context.dataStore.get(HideExplicitKey, false))
                    ?.filterVideoSongs(context.dataStore.get(HideVideoSongsKey, false))
                    ?: emptyList()
                LogBuffer.log("Songs: ${songsResult.size}개")

                val albumsResult = YouTube.search(query, YouTube.SearchFilter.FILTER_ALBUM).getOrNull()
                    ?.items?.filterIsInstance<AlbumItem>()
                    ?: emptyList()
                LogBuffer.log("Albums: ${albumsResult.size}개")

                val artistsResult = YouTube.search(query, YouTube.SearchFilter.FILTER_ARTIST).getOrNull()
                    ?.items?.filterIsInstance<ArtistItem>()
                    ?: emptyList()
                LogBuffer.log("Artists: ${artistsResult.size}개")

                val videosResult = YouTube.search(query, YouTube.SearchFilter.FILTER_VIDEO).getOrNull()
                    ?.items?.filterIsInstance<SongItem>()
                    ?.filterExplicit(context.dataStore.get(HideExplicitKey, false))
                    ?: emptyList()
                LogBuffer.log("Videos: ${videosResult.size}개")

                LogBuffer.log("카테고리별 검색 완료: ${System.currentTimeMillis() - searchStart}ms")

                // 3. 각 섹션을 그룹 hint 박아서 합치기
                val items = mutableListOf<MediaItem>()
                val allSongs = mutableListOf<SongItem>()

                fun addItem(ytItem: Any, groupHint: String) {
                    val mediaId: String
                    val itemTitle: String
                    val itemSubtitle: String?
                    val itemThumbnail: String?
                    val isPlayable: Boolean
                    val isBrowsable: Boolean
                    val mediaType: Int

                    when (ytItem) {
                        is SongItem -> {
                            mediaId = "${MusicService.SEARCH}/$query/${ytItem.id}"
                            itemTitle = ytItem.title
                            itemSubtitle = ytItem.artists.joinToString(", ") { it.name }
                            itemThumbnail = ytItem.thumbnail
                            isPlayable = true
                            isBrowsable = false
                            mediaType = MediaMetadata.MEDIA_TYPE_MUSIC
                            allSongs.add(ytItem)
                        }
                        is AlbumItem -> {
                            mediaId = "${MusicService.ALBUM}/${ytItem.browseId}"
                            itemTitle = ytItem.title
                            itemSubtitle = ytItem.artists?.joinToString(", ") { it.name }
                            itemThumbnail = ytItem.thumbnail
                            isPlayable = false
                            isBrowsable = true
                            mediaType = MediaMetadata.MEDIA_TYPE_ALBUM
                        }
                        is ArtistItem -> {
                            mediaId = "${MusicService.ARTIST}/${ytItem.id}"
                            itemTitle = ytItem.title
                            itemSubtitle = null
                            itemThumbnail = ytItem.thumbnail
                            isPlayable = false
                            isBrowsable = true
                            mediaType = MediaMetadata.MEDIA_TYPE_ARTIST
                        }
                        is PlaylistItem -> {
                            mediaId = "${MusicService.YOUTUBE_PLAYLIST}/${ytItem.id}"
                            itemTitle = ytItem.title
                            itemSubtitle = ytItem.author?.name
                            itemThumbnail = ytItem.thumbnail
                            isPlayable = false
                            isBrowsable = true
                            mediaType = MediaMetadata.MEDIA_TYPE_PLAYLIST
                        }
                        else -> return
                    }

                    val extras = android.os.Bundle().apply {
                        putString("android.media.browse.CONTENT_STYLE_GROUP_TITLE_HINT", groupHint)
                    }

                    items.add(
                        MediaItem.Builder()
                            .setMediaId(mediaId)
                            .setMediaMetadata(
                                MediaMetadata.Builder()
                                    .setTitle(itemTitle)
                                    .setSubtitle(itemSubtitle)
                                    .setArtist(itemSubtitle)
                                    .setArtworkUri(itemThumbnail?.toUri()?.let { AlbumArtContentProvider.mapUri(it) })
                                    .setIsPlayable(isPlayable)
                                    .setIsBrowsable(isBrowsable)
                                    .setMediaType(mediaType)
                                    .setExtras(extras)
                                    .build()
                            )
                            .build()
                    )
                }

                // 순서대로 추가: Top result → Songs → Albums → Artists → Videos
                topResultItems.forEach { addItem(it, "Top result") }
                songsResult.forEach { addItem(it, "Songs") }
                albumsResult.forEach { addItem(it, "Albums") }
                artistsResult.forEach { addItem(it, "Artists") }
                videosResult.forEach { addItem(it, "Videos") }

                lastSearchSongs = allSongs.distinctBy { it.id }
                LogBuffer.log("SEARCH lastSearchSongs 저장: 총 ${lastSearchSongs.size}곡")
                LogBuffer.log("SEARCH items 총 ${items.size}개")

                LibraryResult.ofItemList(items, params)

            } catch (e: Exception) {
                LogBuffer.log("onGetSearchResult 실패: ${e.message}")
                reportException(e)
                LibraryResult.ofItemList(emptyList<MediaItem>(), params)
            }
        }
    }
    override fun onSetMediaItems(
        mediaSession: MediaSession,
        controller: MediaSession.ControllerInfo,
        mediaItems: MutableList<MediaItem>,
        startIndex: Int,
        startPositionMs: Long,
    ): ListenableFuture<MediaItemsWithStartPosition> =
        scope.future {
            val defaultResult = MediaItemsWithStartPosition(emptyList<MediaItem>(), startIndex, startPositionMs)
            LogBuffer.log("onSetMediaItems called: mediaId=${mediaItems.firstOrNull()?.mediaId}, count=${mediaItems.size}")
            val voiceQuery = mediaItems.firstOrNull()?.requestMetadata?.searchQuery

            val path = if (!voiceQuery.isNullOrBlank()) {
                listOf(MusicService.SEARCH, voiceQuery, "")
            } else {
                mediaItems.firstOrNull()?.mediaId?.split("/")
            } ?: return@future defaultResult

            when (path.firstOrNull()) {
                MusicService.SONG -> {
                    val songId = path.getOrNull(1) ?: return@future defaultResult
                    try {
                        val ytSongs: List<SongItem> = YouTube.library("FEmusic_liked_videos").completed().getOrNull()
                            ?.items?.filterIsInstance<SongItem>()
                            ?: emptyList()

                        if (songId == MusicService.SHUFFLE_ACTION) {
                            MediaItemsWithStartPosition(
                                ytSongs.shuffled().map { it.toMediaItem() },
                                0,
                                C.TIME_UNSET
                            )
                        } else {
                            MediaItemsWithStartPosition(
                                ytSongs.map { it.toMediaItem() },
                                ytSongs.indexOfFirst { it.id == songId }.takeIf { idx -> idx != -1 } ?: 0,
                                startPositionMs
                            )
                        }
                    } catch (e: Exception) {
                        reportException(e)
                        defaultResult
                    }
                }

                MusicService.ARTIST -> {
                    val songId = path.getOrNull(2) ?: return@future defaultResult
                    val artistId = path.getOrNull(1) ?: return@future defaultResult
                    try {
                        LogBuffer.log("재생용 ARTIST 곡 가져오기 시작: artistId=$artistId")
                        val artistPage = YouTube.artist(artistId).getOrNull()
                        val firstPageSongs = artistPage?.sections
                            ?.flatMap { it.items }
                            ?.filterIsInstance<SongItem>()
                            ?: emptyList()

                        val songsSectionEndpoint = artistPage?.sections
                            ?.firstOrNull { section ->
                                section.items.any { it is SongItem } && section.moreEndpoint != null
                            }
                            ?.moreEndpoint

                        val moreSongs = mutableListOf<SongItem>()
                        if (songsSectionEndpoint != null) {
                            try {
                                val firstMorePage = YouTube.artistItems(songsSectionEndpoint).getOrNull()
                                firstMorePage?.items?.filterIsInstance<SongItem>()?.let { moreSongs.addAll(it) }

                                var continuation = firstMorePage?.continuation
                                var pageCount = 0
                                val maxContinuationPages = 2
                                while (continuation != null && pageCount < maxContinuationPages) {
                                    val contPage = YouTube.artistItemsContinuation(continuation).getOrNull()
                                    if (contPage == null) break
                                    contPage.items.filterIsInstance<SongItem>().let { moreSongs.addAll(it) }
                                    continuation = contPage.continuation
                                    pageCount++
                                }
                            } catch (e: Exception) {
                                LogBuffer.log("재생용 artistItems 호출 실패: ${e.message}")
                                reportException(e)
                            }
                        }

                        val ytSongs: List<SongItem> = (firstPageSongs + moreSongs).distinctBy { it.id }
                        LogBuffer.log("재생용 ARTIST 최종 곡 수=${ytSongs.size}")

                        if (songId == MusicService.SHUFFLE_ACTION) {
                            MediaItemsWithStartPosition(
                                ytSongs.shuffled().map { it.toMediaItem() },
                                0,
                                C.TIME_UNSET
                            )
                        } else {
                            MediaItemsWithStartPosition(
                                ytSongs.map { it.toMediaItem() },
                                ytSongs.indexOfFirst { it.id == songId }.takeIf { idx -> idx != -1 } ?: 0,
                                startPositionMs
                            )
                        }
                    } catch (e: Exception) {
                        reportException(e)
                        defaultResult
                    }
                }

                MusicService.ALBUM -> {
                    val songId = path.getOrNull(2) ?: return@future defaultResult
                    val albumId = path.getOrNull(1) ?: return@future defaultResult
                    try {
                        val ytSongs: List<SongItem> = YouTube.album(albumId).getOrNull()?.songs
                            ?: emptyList()

                        if (songId == MusicService.SHUFFLE_ACTION) {
                            MediaItemsWithStartPosition(
                                ytSongs.shuffled().map { it.toMediaItem() },
                                0,
                                C.TIME_UNSET
                            )
                        } else {
                            MediaItemsWithStartPosition(
                                ytSongs.map { it.toMediaItem() },
                                ytSongs.indexOfFirst { it.id == songId }.takeIf { idx -> idx != -1 } ?: 0,
                                startPositionMs
                            )
                        }
                    } catch (e: Exception) {
                        reportException(e)
                        defaultResult
                    }
                }

                MusicService.PLAYLIST -> {
                    val songId = path.getOrNull(2) ?: return@future defaultResult
                    val playlistId = path.getOrNull(1) ?: return@future defaultResult

                    if (playlistId == PlaylistEntity.LIKED_PLAYLIST_ID) {
                        return@future try {
                            val rawSongs: List<SongItem> = YouTube.playlist("LM").completed().getOrNull()
                                ?.songs
                                ?: emptyList()

                            // 화면 표시 목록과 동일한 필터를 재생 큐에도 적용해야
                            // "목록엔 없는데 재생되는 곡"(차에서 해제한 곡)이 안 끼어든다.
                            val unlikedIds = try {
                                rawSongs.mapNotNull { songItem ->
                                    val dbSong = try {
                                        database.song(songItem.id).first()
                                    } catch (e: Exception) {
                                        null
                                    }
                                    if (dbSong != null && dbSong.song.liked == false) songItem.id else null
                                }.toSet()
                            } catch (e: Exception) {
                                emptySet()
                            }

                            val ytSongs: List<SongItem> = rawSongs
                                .distinctBy { it.id }
                                .filterNot { it.id in unlikedIds }

                            LogBuffer.log("LIKED playback: 원본=${rawSongs.size}, 필터후=${ytSongs.size}, songId=$songId, firstTitle=${ytSongs.firstOrNull()?.title}")
                            // 진단: 클릭한 곡이 로컬 DB에 있는지, liked 값이 뭔지 확인
                            try {
                                val targetSongId = if (songId == MusicService.SHUFFLE_ACTION) {
                                    ytSongs.firstOrNull()?.id
                                } else {
                                    songId
                                }
                                if (targetSongId != null) {
                                    val dbSong = database.song(targetSongId).first()
                                    if (dbSong == null) {
                                        LogBuffer.log("LIKED 진단: targetId=$targetSongId, DB에 없음")
                                    } else {
                                        LogBuffer.log("LIKED 진단: targetId=$targetSongId, DB에 있음, liked=${dbSong.song.liked}, title=${dbSong.song.title}")
                                    }
                                } else {
                                    LogBuffer.log("LIKED 진단: targetSongId가 null")
                                }
                            } catch (e: Exception) {
                                LogBuffer.log("LIKED 진단 실패: ${e.message}")
                            }


                            if (songId == MusicService.SHUFFLE_ACTION) {
                                MediaItemsWithStartPosition(
                                    ytSongs.shuffled().map { it.toMediaItem() },
                                    0,
                                    C.TIME_UNSET
                                )
                            } else {
                                MediaItemsWithStartPosition(
                                    ytSongs.map { it.toMediaItem() },
                                    ytSongs.indexOfFirst { it.id == songId }.takeIf { idx -> idx != -1 } ?: 0,
                                    startPositionMs
                                )
                            }
                        } catch (e: Exception) {
                            reportException(e)
                            defaultResult
                        }
                    }

                    val songs: List<Song> = when (playlistId) {
                        PlaylistEntity.DOWNLOADED_PLAYLIST_ID -> {
                            val downloads = downloadUtil.downloads.value
                            database
                                .allSongs()
                                .flowOn(Dispatchers.IO)
                                .map { allSongs ->
                                    allSongs.filter {
                                        downloads[it.id]?.state == Download.STATE_COMPLETED
                                    }
                                }.map { filteredSongs ->
                                    filteredSongs
                                        .map { it to downloads[it.id] }
                                        .sortedBy { it.second?.updateTimeMs ?: 0L }
                                        .map { it.first }
                                }.first()
                        }
                        else -> database.playlistSongs(playlistId).map { list ->
                            list.map { it.song }
                        }.first()
                    }

                    if (songId == MusicService.SHUFFLE_ACTION) {
                        MediaItemsWithStartPosition(
                            songs.shuffled().map { it.toMediaItem() },
                            0,
                            C.TIME_UNSET
                        )
                    } else {
                        MediaItemsWithStartPosition(
                            songs.map { it.toMediaItem() },
                            songs.indexOfFirst { it.id == songId }.takeIf { idx -> idx != -1 } ?: 0,
                            startPositionMs
                        )
                    }
                }

                MusicService.YOUTUBE_PLAYLIST -> {
                    val songId = path.getOrNull(2) ?: return@future defaultResult
                    val playlistId = path.getOrNull(1) ?: return@future defaultResult

                    val songs: List<MediaItem> = try {
                        YouTube.playlist(playlistId).getOrNull()?.songs?.map {
                            it.toMediaItem()
                        } ?: emptyList()
                    } catch (e: Exception) {
                        reportException(e)
                        return@future defaultResult
                    }

                    if (songId == MusicService.SHUFFLE_ACTION) {
                        MediaItemsWithStartPosition(
                            songs.shuffled(),
                            0,
                            C.TIME_UNSET
                        )
                    } else {
                        MediaItemsWithStartPosition(
                            songs,
                            songs.indexOfFirst { it.mediaId.endsWith(songId) }.takeIf { idx -> idx != -1 } ?: 0,
                            C.TIME_UNSET
                        )
                    }
                }

                MusicService.RECOMMENDED -> {
                    val songId = path.getOrNull(1) ?: return@future defaultResult
                    val ytSongs = lastRecommendedSongs
                    if (ytSongs.isEmpty()) return@future defaultResult

                    if (songId == MusicService.SHUFFLE_ACTION) {
                        MediaItemsWithStartPosition(
                            ytSongs.shuffled().map { it.toMediaItem() },
                            0,
                            C.TIME_UNSET
                        )
                    } else {
                        MediaItemsWithStartPosition(
                            ytSongs.map { it.toMediaItem() },
                            ytSongs.indexOfFirst { it.id == songId }.takeIf { idx -> idx != -1 } ?: 0,
                            C.TIME_UNSET
                        )
                    }
                }

                MusicService.SEARCH -> {
                    // mediaId = "search/검색어/곡id" 인데, 검색어에 '/'나 공백이 섞이면
                    // split 결과가 밀려서 path[2]가 곡 id가 아니게 된다.
                    // 곡 id는 항상 맨 마지막 조각이므로 last()로 가져온다.
                    val songId = path.lastOrNull()?.takeIf { it.isNotBlank() } ?: return@future defaultResult
                    // 검색어는 search/ 와 마지막 곡id 를 뺀 가운데 전부 (원래 '/'가 있었으면 복원)
                    val searchQuery = if (path.size > 2) {
                        path.subList(1, path.size - 1).joinToString("/")
                    } else {
                        path.getOrNull(1) ?: return@future defaultResult
                    }

                    val cachedSongs = lastSearchSongs
                    if (cachedSongs.isNotEmpty()) {
                        val tIdx = cachedSongs.indexOfFirst { it.id == songId }
                        return@future MediaItemsWithStartPosition(
                            cachedSongs.map { it.toMediaItem() },
                            if (tIdx >= 0) tIdx else 0,
                            C.TIME_UNSET
                        )
                    }

                    val searchResults = mutableListOf<Song>()

                    val localSongs = database.allSongs().first().filter { song ->
                        song.song.title.contains(searchQuery, ignoreCase = true) ||
                                song.artists.any { it.name.contains(searchQuery, ignoreCase = true) } ||
                                song.album?.title?.contains(searchQuery, ignoreCase = true) == true
                    }

                    val artistSongs = database.searchArtists(searchQuery).first().flatMap { artist ->
                        database.artistSongsByCreateDateAsc(artist.id).first()
                    }

                    val albumSongs = database.searchAlbums(searchQuery).first().flatMap { album ->
                        database.albumSongs(album.id).first()
                    }

                    val playlistSongs = database.searchPlaylists(searchQuery).first().flatMap { playlist ->
                        database.playlistSongs(playlist.id).first().map { it.song }
                    }

                    val allLocalSongs = (localSongs + artistSongs + albumSongs + playlistSongs)
                        .distinctBy { it.id }

                    searchResults.addAll(allLocalSongs)

                    try {
                        val onlineResults = YouTube.search(searchQuery, YouTube.SearchFilter.FILTER_SONG)
                            .getOrNull()
                            ?.items
                            ?.filterIsInstance<SongItem>()
                            ?.filterExplicit(context.dataStore.get(HideExplicitKey, false))
                            ?.filterVideoSongs(context.dataStore.get(HideVideoSongsKey, false))
                            ?.filter { onlineSong ->
                                !allLocalSongs.any { localSong ->
                                    localSong.id == onlineSong.id ||
                                            (localSong.song.title.equals(onlineSong.title, ignoreCase = true) &&
                                                    localSong.artists.any { artist ->
                                                        onlineSong.artists.any {
                                                            it.name.equals(artist.name, ignoreCase = true)
                                                        }
                                                    })
                                }
                            } ?: emptyList()

                        onlineResults.forEachIndexed { idx, songItem ->
                            LogBuffer.log("SEARCH 결과[$idx]: id=${songItem.id}, title=${songItem.title}")
                            try {
                                database.query { insert(songItem.toMediaMetadata()) }
                                database.song(songItem.id).first()?.let { newSong ->
                                    searchResults.add(newSong)
                                }
                            } catch (e: Exception) {
                            }
                        }
                    } catch (e: Exception) {
                        reportException(e)
                    }

                    if (searchResults.isEmpty()) {
                        return@future defaultResult
                    }

                    val targetIndex = searchResults.indexOfFirst { it.id == songId }

                    MediaItemsWithStartPosition(
                        searchResults.map { it.toMediaItem() },
                        if (targetIndex >= 0) targetIndex else 0,
                        C.TIME_UNSET
                    )
                }

                else -> defaultResult
            }
        }

    private fun drawableUri(
        @DrawableRes id: Int,
    ) = Uri
        .Builder()
        .scheme(ContentResolver.SCHEME_ANDROID_RESOURCE)
        .authority(context.resources.getResourcePackageName(id))
        .appendPath(context.resources.getResourceTypeName(id))
        .appendPath(context.resources.getResourceEntryName(id))
        .build()

    private fun browsableMediaItem(
        id: String,
        title: String,
        subtitle: String?,
        iconUri: Uri?,
        mediaType: Int = MediaMetadata.MEDIA_TYPE_MUSIC,
    ) = MediaItem
        .Builder()
        .setMediaId(id)
        .setMediaMetadata(
            MediaMetadata
                .Builder()
                .setTitle(title)
                .setSubtitle(subtitle)
                .setArtist(subtitle)
                .setArtworkUri(iconUri)
                .setIsPlayable(false)
                .setIsBrowsable(true)
                .setMediaType(mediaType)
                .build(),
        ).build()

    private fun Song.toMediaItem(path: String, isPlayable: Boolean = true, isBrowsable: Boolean = false): MediaItem {
        return MediaItem
            .Builder()
            .setMediaId("$path/$id")
            .setMediaMetadata(
                MediaMetadata
                    .Builder()
                    .setTitle(song.title)
                    .setSubtitle(artists.joinToString { it.name })
                    .setArtist(artists.joinToString { it.name })
                    .setArtworkUri(song.thumbnailUrl?.toUri()?.let { AlbumArtContentProvider.mapUri(it) })
                    .setIsPlayable(isPlayable)
                    .setIsBrowsable(isBrowsable)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                    .build(),
            ).build()
    }
}