/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.playback

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
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
import coil3.imageLoader
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
import com.metrolist.innertube.utils.parseCookieString
import com.metrolist.music.R
import com.metrolist.music.automotive.AutomotiveLoginActivity
import com.metrolist.music.constants.AndroidAutoSectionsOrderKey
import com.metrolist.music.constants.AndroidAutoYouTubePlaylistsKey
import com.metrolist.music.constants.HideExplicitKey
import com.metrolist.music.constants.HideVideoSongsKey
import com.metrolist.music.constants.InnerTubeCookieKey
import com.metrolist.music.constants.MediaSessionConstants
import com.metrolist.music.constants.SongSortType
import com.metrolist.music.db.MusicDatabase
import com.metrolist.music.db.entities.PlaylistEntity
import com.metrolist.music.db.entities.Song
import com.metrolist.music.extensions.toMediaItem
import com.metrolist.music.extensions.toggleRepeatMode
import com.metrolist.music.models.toMediaMetadata
import com.metrolist.music.ui.screens.settings.AndroidAutoSection
import com.metrolist.music.ui.screens.settings.deserializeSections
import com.metrolist.music.ui.screens.settings.serializeSections
import com.metrolist.music.utils.dataStore
import com.metrolist.music.utils.get
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
    ): ListenableFuture<LibraryResult<MediaItem>> =
        Futures.immediateFuture(
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
                params,
            ),
        )

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
            val items: List<MediaItem> = when (parentId) {
                MusicService.ROOT -> {
                    val sectionsRaw = context.dataStore.get(
                        AndroidAutoSectionsOrderKey,
                        serializeSections(AndroidAutoSection.values().map { it to true })
                    )
                    val sections = listOf(
                        AndroidAutoSection.LIKED to true,
                        AndroidAutoSection.SONGS to true,
                        AndroidAutoSection.ARTISTS to true,
                        AndroidAutoSection.RECOMMENDED to true,
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
                                AndroidAutoSection.RECOMMENDED -> browsableMediaItem(
                                    MusicService.RECOMMENDED,
                                    context.getString(R.string.android_auto_recommended),
                                    null,
                                    drawableUri(R.drawable.explore_outlined),
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
                        val result = YouTube.library("FEmusic_liked_videos").completed().getOrNull()
                            ?.items?.filterIsInstance<SongItem>()
                            ?.filterExplicit(context.dataStore.get(HideExplicitKey, false))
                            ?.filterVideoSongs(context.dataStore.get(HideVideoSongsKey, false))
                            ?: emptyList()
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
                    LogBuffer.log("YouTube.library(FEmusic_library_corpus_artists) 호출 시작")
                    val artistList: List<ArtistItem> = try {
                        val result = YouTube.library("FEmusic_library_corpus_artists").completed().getOrNull()
                            ?.items?.filterIsInstance<ArtistItem>()
                            ?: emptyList()
                        LogBuffer.log("YouTube.library(FEmusic_library_corpus_artists) 완료, ${System.currentTimeMillis() - artistStart}ms, artists=${result.size}")
                        result
                    } catch (e: Exception) {
                        LogBuffer.log("YouTube.library(FEmusic_library_corpus_artists) 실패, ${System.currentTimeMillis() - artistStart}ms: ${e.message}")
                        reportException(e)
                        emptyList()
                    }

                    LogBuffer.log("ARTIST count=${artistList.size}, first=${artistList.firstOrNull()?.title}")

                    artistList.map { artist ->
                        browsableMediaItemWithArtwork(
                            "${MusicService.ARTIST}/${artist.id}",
                            artist.title,
                            null,
                            artist.thumbnail,
                            MediaMetadata.MEDIA_TYPE_ARTIST,
                        )
                    }
                }

                MusicService.ALBUM -> {
                    val albumList: List<AlbumItem> = try {
                        YouTube.library("FEmusic_liked_albums").completed().getOrNull()
                            ?.items?.filterIsInstance<AlbumItem>()
                            ?: emptyList()
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
                    val likedSongCount = database.likedSongsCount().first()
                    val downloadedSongCount = downloadUtil.downloads.value.size

                    listOf(
                        browsableMediaItem(
                            "${MusicService.PLAYLIST}/${PlaylistEntity.LIKED_PLAYLIST_ID}",
                            context.getString(R.string.liked_songs),
                            context.resources.getQuantityString(R.plurals.n_song, likedSongCount, likedSongCount),
                            drawableUri(R.drawable.favorite),
                            MediaMetadata.MEDIA_TYPE_PLAYLIST,
                        ),
                        browsableMediaItem(
                            "${MusicService.PLAYLIST}/${PlaylistEntity.DOWNLOADED_PLAYLIST_ID}",
                            context.getString(R.string.downloaded_songs),
                            context.resources.getQuantityString(R.plurals.n_song, downloadedSongCount, downloadedSongCount),
                            drawableUri(R.drawable.download),
                            MediaMetadata.MEDIA_TYPE_PLAYLIST,
                        ),
                    ) + database.playlistsByCreateDateAsc().first().map { playlist ->
                        browsableMediaItem(
                            "${MusicService.PLAYLIST}/${playlist.id}",
                            playlist.playlist.name,
                            context.resources.getQuantityString(R.plurals.n_song, playlist.songCount, playlist.songCount),
                            playlist.thumbnails.firstOrNull()?.toUri(),
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
                            LogBuffer.log("YouTube.home() 호출 시작 (RECOMMENDED, page=$page)")
                            val result = YouTube.home(continuation)
                                .onFailure { LogBuffer.log("YouTube.home() 실패 (RECOMMENDED, page=$page): ${it.message}"); reportException(it) }
                                .getOrNull()
                            LogBuffer.log("YouTube.home() 완료 (RECOMMENDED, page=$page), ${System.currentTimeMillis() - homeStart}ms, sections=${result?.sections?.size ?: -1}")
                            if (result == null) break
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

                        lastRecommendedSongs = songs

                        LogBuffer.log("RECOMMENDED songs count=${songs.size}")

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

                else -> {
                    when {
                        parentId.startsWith("${MusicService.ARTIST}/") -> {
                            val artistId = parentId.removePrefix("${MusicService.ARTIST}/")
                            val artistFetchStart = System.currentTimeMillis()
                            LogBuffer.log("YouTube.artist($artistId) 호출 시작")
                            val songs: List<SongItem> = try {
                                val artistPage = YouTube.artist(artistId).getOrNull()
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
                                YouTube.album(albumId).getOrNull()?.songs
                                    ?.filterExplicit(context.dataStore.get(HideExplicitKey, false))
                                    ?.filterVideoSongs(context.dataStore.get(HideVideoSongsKey, false))
                                    ?: emptyList()
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
                                    YouTube.library("FEmusic_liked_videos").completed().getOrNull()
                                        ?.items?.filterIsInstance<SongItem>()
                                        ?.filterExplicit(context.dataStore.get(HideExplicitKey, false))
                                        ?.filterVideoSongs(context.dataStore.get(HideVideoSongsKey, false))
                                        ?: emptyList()
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
                                YouTube.playlist(playlistId).getOrNull()?.songs
                                    ?.take(100)
                                    ?.filterExplicit(context.dataStore.get(HideExplicitKey, false))
                                    ?.filterVideoSongs(context.dataStore.get(HideVideoSongsKey, false))
                                    ?: emptyList()
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
            database.song(mediaId).first()?.toMediaItem()?.let {
                LibraryResult.ofItem(it, null)
            } ?: LibraryResult.ofError(SessionError.ERROR_UNKNOWN)
        }

    override fun onSearch(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        query: String,
        params: MediaLibraryService.LibraryParams?
    ): ListenableFuture<LibraryResult<Void>> {
        session.notifySearchResultChanged(browser, query, 1, params)
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
                val searchResults = mutableListOf<MediaItem>()

                val localSongs = database.allSongs().first().filter { song ->
                    song.song.title.contains(query, ignoreCase = true) ||
                            song.artists.any { it.name.contains(query, ignoreCase = true) } ||
                            song.album?.title?.contains(query, ignoreCase = true) == true
                }

                val artistSongs = database.searchArtists(query).first().flatMap { artist ->
                    database.artistSongsByCreateDateAsc(artist.id).first()
                }

                val albumSongs = database.searchAlbums(query).first().flatMap { album ->
                    database.albumSongs(album.id).first()
                }

                val playlistSongs = database.searchPlaylists(query).first().flatMap { playlist ->
                    database.playlistSongs(playlist.id).first().map { it.song }
                }

                val allLocalSongs = (localSongs + artistSongs + albumSongs + playlistSongs)
                    .distinctBy { it.id }

                allLocalSongs.forEach { song ->
                    searchResults.add(song.toMediaItem(
                        path = "${MusicService.SEARCH}/$query",
                        isPlayable = true,
                        isBrowsable = false
                    ))
                }

                try {
                    val searchStart = System.currentTimeMillis()
                    LogBuffer.log("YouTube.search() 호출 시작 (onGetSearchResult, query=$query)")
                    val onlineResults = YouTube.search(query, YouTube.SearchFilter.FILTER_SONG)
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

                    lastSearchSongs = onlineResults
                    LogBuffer.log("YouTube.search() 완료 (onGetSearchResult), ${System.currentTimeMillis() - searchStart}ms, results=${onlineResults.size}")

                    onlineResults.forEach { songItem ->
                        try {
                            database.query { insert(songItem.toMediaMetadata()) }
                        } catch (e: Exception) {
                        }

                        searchResults.add(
                            MediaItem.Builder()
                                .setMediaId("${MusicService.SEARCH}/$query/${songItem.id}")
                                .setMediaMetadata(
                                    MediaMetadata.Builder()
                                        .setTitle(songItem.title)
                                        .setSubtitle(songItem.artists.joinToString(", ") { it.name })
                                        .setArtist(songItem.artists.joinToString(", ") { it.name })
                                        .setArtworkUri(songItem.thumbnail.toUri().let { AlbumArtContentProvider.mapUri(it) })
                                        .setIsPlayable(true)
                                        .setIsBrowsable(false)
                                        .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                                        .build()
                                )
                                .build()
                        )
                    }
                } catch (e: Exception) {
                    LogBuffer.log("YouTube.search() 실패 (onGetSearchResult): ${e.message}")
                    reportException(e)
                }

                LibraryResult.ofItemList(searchResults, params)

            } catch (e: Exception) {
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
                            val ytSongs: List<SongItem> = YouTube.library("FEmusic_liked_videos").completed().getOrNull()
                                ?.items?.filterIsInstance<SongItem>()
                                ?: emptyList()

                            LogBuffer.log("LIKED playback: ytSongs.size=${ytSongs.size}, songId=$songId, firstTitle=${ytSongs.firstOrNull()?.title}")

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
                    val songId = path.getOrNull(2) ?: return@future defaultResult
                    val searchQuery = path.getOrNull(1) ?: return@future defaultResult

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

                        onlineResults.forEach { songItem ->
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