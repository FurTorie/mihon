package eu.kanade.tachiyomi.data.backup.restore.restorers

import app.cash.sqldelight.async.coroutines.awaitAsList
import app.cash.sqldelight.async.coroutines.awaitAsOne
import app.cash.sqldelight.async.coroutines.awaitAsOneOrNull
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedFactory
import dev.zacsweers.metro.AssistedInject
import eu.kanade.domain.manga.interactor.UpdateManga
import eu.kanade.tachiyomi.data.backup.models.BackupCategory
import eu.kanade.tachiyomi.data.backup.models.BackupChapter
import eu.kanade.tachiyomi.data.backup.models.BackupHistory
import eu.kanade.tachiyomi.data.backup.models.BackupManga
import eu.kanade.tachiyomi.data.backup.models.BackupTracking
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import mihon.sync.merge.SyncMergePolicy
import mihon.sync.merge.SyncMergePolicy.ChapterSet
import tachiyomi.data.Database
import tachiyomi.data.MemoColumnAdapter
import tachiyomi.data.UpdateStrategyColumnAdapter
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.interactor.FetchInterval
import tachiyomi.domain.manga.interactor.GetMangaByUrlAndSourceId
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.track.interactor.GetTracks
import tachiyomi.domain.track.interactor.InsertTrack
import tachiyomi.domain.track.model.Track
import java.util.Date
import kotlin.math.max
import kotlin.time.Clock

@AssistedInject
class MangaRestorer(
    /**
     * A sync merges two live devices, so a missing favourite or category means "removed here" and
     * has to be able to win. A manual restore replays a file that may be months old, where the same
     * absence means nothing: there, state is only ever added.
     */
    @Assisted private val isSync: Boolean,
    private val database: Database,
    private val getCategories: GetCategories,
    private val getMangaByUrlAndSourceId: GetMangaByUrlAndSourceId,
    private val getChaptersByMangaId: GetChaptersByMangaId,
    private val updateManga: UpdateManga,
    private val getTracks: GetTracks,
    private val insertTrack: InsertTrack,
    fetchInterval: FetchInterval,
) {

    @AssistedFactory
    fun interface Factory {
        fun create(isSync: Boolean): MangaRestorer
    }

    private val timeZone = TimeZone.currentSystemDefault()
    private val now = Clock.System.now().toLocalDateTime(timeZone)
    private val currentFetchWindow = fetchInterval.getWindow(now.date, timeZone)

    suspend fun sortByNew(backupMangas: List<BackupManga>): List<BackupManga> {
        val urlsBySource = database.mangasQueries
            .getAllMangaSourceAndUrl()
            .awaitAsList()
            .groupBy({ it.source }, { it.url })

        return backupMangas
            .sortedWith(
                compareBy<BackupManga> { it.url in urlsBySource[it.source].orEmpty() }
                    .then(compareByDescending { it.lastModifiedAt }),
            )
    }

    /**
     * @param withCategories false to leave the entry's categories exactly as they are, for a payload
     * whose references to categories cannot be resolved here.
     */
    suspend fun restore(
        backupManga: BackupManga,
        backupCategories: List<BackupCategory>,
        withCategories: Boolean = true,
    ) {
        database.transaction {
            val dbManga = findExistingManga(backupManga)
            val manga = backupManga.getMangaImpl()
            val chapterSet = SyncMergePolicy.resolveChapterSet(
                isSync = isSync && dbManga != null,
                localListAt = dbManga?.lastUpdate ?: 0,
                incomingListAt = backupManga.chapterListAt,
            )
            val restoredManga = if (dbManga == null) {
                // A new entry's chapter list is the incoming one, as of when that device refreshed it.
                restoreNewManga(manga.copy(lastUpdate = backupManga.chapterListAt))
            } else {
                restoreExistingManga(manga, dbManga, chapterSet, backupManga.chapterListAt)
            }

            // Category membership is replaced wholesale rather than merged, so only one side may
            // drive it. In a restore that is the side holding the newer state. In a sync it is the
            // incoming side, which published because something changed there — as for the other
            // choices made for an entry, a newer version only means more reading was done.
            val incomingOwnsCategories = isSync || dbManga == null || manga.version >= dbManga.version

            restoreMangaDetails(
                manga = restoredManga,
                chapters = backupManga.chapters,
                chapterSet = chapterSet,
                categories = backupManga.categories.takeIf { withCategories },
                backupCategories = backupCategories,
                incomingOwnsCategories = incomingOwnsCategories,
                history = backupManga.history,
                tracks = backupManga.tracking,
                excludedScanlators = backupManga.excludedScanlators,
            )
        }
    }

    private suspend fun findExistingManga(backupManga: BackupManga): Manga? {
        return getMangaByUrlAndSourceId.await(backupManga.url, backupManga.source)
    }

    private suspend fun restoreExistingManga(
        manga: Manga,
        dbManga: Manga,
        chapterSet: ChapterSet,
        incomingListAt: Long,
    ): Manga {
        val newest = if (manga.version > dbManga.version) {
            dbManga.copyFrom(manga)
        } else {
            manga.copyFrom(dbManga)
        }

        // Which side's metadata is newer says nothing about who last changed how the entry is read.
        // Reading bumps the version, so the device where a reading mode was just changed is usually
        // the newer one — and keeping the local choices then sent the old mode straight back to it.
        // In a sync the incoming side published because something changed there, so its choices win.
        val merged = if (isSync) newest.withChoicesOf(manga) else newest

        return updateManga(
            merged.withDeviceStateOf(dbManga, chapterSet, incomingListAt).copy(
                id = dbManga.id,
                favorite = resolveFavorite(local = dbManga, incoming = manga),
                favoriteModifiedAt = SyncMergePolicy.newestTimestamp(
                    dbManga.favoriteModifiedAt,
                    manga.favoriteModifiedAt,
                ),
            ),
        )
    }

    private fun resolveFavorite(local: Manga, incoming: Manga): Boolean = SyncMergePolicy.resolveFavorite(
        isSync = isSync,
        localFavorite = local.favorite,
        localModifiedAt = local.favoriteModifiedAt,
        incomingFavorite = incoming.favorite,
        incomingModifiedAt = incoming.favoriteModifiedAt,
    )

    /**
     * What only this device knows stays as it is: when its chapter list last changed from the source
     * — unless a sync is replacing that list with the incoming one — and when its custom cover
     * changed. A backup carries neither, so taking them from the incoming side reset both to zero,
     * and the library's sorting by last update with them: once per restore, and on every merge once
     * restores became the way devices sync.
     */
    private fun Manga.withDeviceStateOf(dbManga: Manga, chapterSet: ChapterSet, incomingListAt: Long): Manga =
        copy(
            lastUpdate = if (chapterSet == ChapterSet.Incoming) incomingListAt else dbManga.lastUpdate,
            coverLastModified = dbManga.coverLastModified,
        )

    /**
     * What the user decided for this entry, as opposed to what the source says about it.
     */
    private fun Manga.withChoicesOf(other: Manga): Manga {
        return this.copy(
            viewerFlags = other.viewerFlags,
            chapterFlags = other.chapterFlags,
            notes = other.notes,
            updateStrategy = other.updateStrategy,
        )
    }

    private fun Manga.copyFrom(newer: Manga): Manga {
        return this.copy(
            author = newer.author,
            artist = newer.artist,
            description = newer.description,
            genre = newer.genre,
            thumbnailUrl = newer.thumbnailUrl,
            status = newer.status,
            initialized = this.initialized || newer.initialized,
            version = newer.version,
        )
    }

    private suspend fun updateManga(manga: Manga): Manga {
        database.mangasQueries.update(
            source = manga.source,
            url = manga.url,
            artist = manga.artist,
            author = manga.author,
            description = manga.description,
            genre = manga.genre?.joinToString(separator = ", "),
            title = manga.title,
            status = manga.status,
            thumbnailUrl = manga.thumbnailUrl,
            favorite = manga.favorite,
            // Imposed explicitly: the trigger is suppressed here (is_syncing = 1) precisely so the
            // merged timestamp survives instead of being reset to now.
            favoriteModifiedAt = manga.favoriteModifiedAt,
            lastUpdate = manga.lastUpdate,
            nextUpdate = null,
            calculateInterval = null,
            initialized = manga.initialized,
            viewer = manga.viewerFlags,
            chapterFlags = manga.chapterFlags,
            coverLastModified = manga.coverLastModified,
            dateAdded = manga.dateAdded,
            mangaId = manga.id,
            updateStrategy = manga.updateStrategy.let(UpdateStrategyColumnAdapter::encode),
            version = manga.version,
            isSyncing = 1,
            notes = manga.notes,
            memo = manga.memo.let(MemoColumnAdapter::encode),
        )
        return manga
    }

    private suspend fun restoreNewManga(
        manga: Manga,
    ): Manga {
        return manga.copy(
            id = insertManga(manga),
        )
    }

    private suspend fun restoreChapters(manga: Manga, backupChapters: List<BackupChapter>, chapterSet: ChapterSet) {
        val dbChaptersByUrl = getChaptersByMangaId.await(manga.id)
            .associateBy { it.url }

        val (existingChapters, newChapters) = backupChapters
            .mapNotNull {
                val chapter = it.toChapterImpl().copy(mangaId = manga.id)

                val dbChapter = dbChaptersByUrl[chapter.url]
                    ?: // New chapter
                    return@mapNotNull chapter

                if (chapter.forComparison() == dbChapter.forComparison()) {
                    // Same state; skip
                    return@mapNotNull null
                }

                // Update to an existing chapter. `copyFrom` only carries metadata across, so the
                // read state below is what decides whether progress survives the merge.
                val resolved = SyncMergePolicy.resolveChapterState(
                    isSync = isSync,
                    local = SyncMergePolicy.ChapterState(
                        read = dbChapter.read,
                        lastPageRead = dbChapter.lastPageRead,
                        decidedAt = dbChapter.readModifiedAt,
                        bookmark = dbChapter.bookmark,
                    ),
                    incoming = SyncMergePolicy.ChapterState(
                        read = chapter.read,
                        lastPageRead = chapter.lastPageRead,
                        decidedAt = chapter.readModifiedAt,
                        bookmark = chapter.bookmark,
                    ),
                )

                chapter
                    .copyFrom(dbChapter)
                    .copy(
                        id = dbChapter.id,
                        bookmark = resolved.bookmark,
                        read = resolved.read,
                        lastPageRead = resolved.lastPageRead,
                        readModifiedAt = resolved.decidedAt,
                    )
            }
            .partition { it.id > 0 }

        // The incoming device refreshed later, so what only this device still lists is gone from the
        // source. An empty incoming list proves nothing of the kind, so it never empties this one.
        if (chapterSet == ChapterSet.Incoming && backupChapters.isNotEmpty()) {
            val listed = backupChapters.mapTo(HashSet()) { it.url }
            val gone = dbChaptersByUrl.values.filter { it.url !in listed }.map { it.id }
            if (gone.isNotEmpty()) database.chaptersQueries.removeChaptersWithIds(gone)
        }

        // This device refreshed later, so what only the incoming copy lists left the source since.
        if (chapterSet != ChapterSet.Local) insertNewChapters(newChapters)
        updateExistingChapters(existingChapters)
    }

    /**
     * The fields worth comparing before deciding a chapter needs no update.
     *
     * [Chapter.readModifiedAt] deliberately counts. It is when the reading state was decided, and
     * two devices that merged the same change at different moments hold different values for it —
     * so skipping on everything else being equal left them permanently disagreeing, which in turn
     * made each one publish the entry back to the other on every single round.
     */
    private fun Chapter.forComparison() =
        this.copy(
            id = 0L,
            mangaId = 0L,
            dateFetch = 0L,
            dateUpload = 0L,
            lastModifiedAt = 0L,
            version = 0L,
        )

    private suspend fun insertNewChapters(chapters: List<Chapter>) {
        database.transaction {
            chapters.forEach { chapter ->
                database.chaptersQueries.insert(
                    chapter.mangaId,
                    chapter.url,
                    chapter.name,
                    chapter.scanlator,
                    chapter.read,
                    chapter.bookmark,
                    chapter.lastPageRead,
                    chapter.chapterNumber,
                    chapter.sourceOrder,
                    chapter.dateFetch,
                    chapter.dateUpload,
                    chapter.version,
                    chapter.memo,
                    chapter.readModifiedAt,
                )
            }
        }
    }

    private suspend fun updateExistingChapters(chapters: List<Chapter>) {
        if (chapters.isEmpty()) return

        database.transaction {
            chapters.forEach { chapter ->
                database.chaptersQueries.update(
                    mangaId = null,
                    url = null,
                    name = null,
                    scanlator = null,
                    read = chapter.read,
                    bookmark = chapter.bookmark,
                    lastPageRead = chapter.lastPageRead,
                    chapterNumber = null,
                    sourceOrder = null,
                    dateFetch = null,
                    dateUpload = null,
                    chapterId = chapter.id,
                    version = chapter.version,
                    // Suppresses update_chapter_and_manga_version, which would stamp
                    // read_modified_at with this device's clock and throw away the timestamp the
                    // merge just decided — leaving the two devices unable to ever agree on it.
                    isSyncing = 1,
                    memo = chapter.memo.let(MemoColumnAdapter::encode),
                    readModifiedAt = chapter.readModifiedAt,
                )
            }

            // Clearing the flag touches none of the reading-state columns, so the trigger stays
            // quiet and the timestamps written above survive.
            database.chaptersQueries.resetIsSyncing()
        }
    }

    /**
     * Inserts manga and returns id
     *
     * @return id of [Manga], null if not found
     */
    private suspend fun insertManga(manga: Manga): Long {
        return database.mangasQueries.insertReturningId(
            source = manga.source,
            url = manga.url,
            artist = manga.artist,
            author = manga.author,
            description = manga.description,
            genre = manga.genre,
            title = manga.title,
            status = manga.status,
            thumbnailUrl = manga.thumbnailUrl,
            favorite = manga.favorite,
            lastUpdate = manga.lastUpdate,
            nextUpdate = 0L,
            calculateInterval = 0L,
            initialized = manga.initialized,
            viewerFlags = manga.viewerFlags,
            chapterFlags = manga.chapterFlags,
            coverLastModified = manga.coverLastModified,
            dateAdded = manga.dateAdded,
            updateStrategy = manga.updateStrategy,
            version = manga.version,
            notes = manga.notes,
            memo = manga.memo,
        )
            .awaitAsOne()
    }

    private suspend fun restoreMangaDetails(
        manga: Manga,
        chapters: List<BackupChapter>,
        chapterSet: ChapterSet,
        categories: List<Long>?,
        backupCategories: List<BackupCategory>,
        incomingOwnsCategories: Boolean,
        history: List<BackupHistory>,
        tracks: List<BackupTracking>,
        excludedScanlators: List<String>,
    ): Manga {
        if (categories != null) restoreCategories(manga, categories, backupCategories, incomingOwnsCategories)
        restoreChapters(manga, chapters, chapterSet)
        restoreTracking(manga, tracks)
        restoreHistory(manga, history)
        restoreExcludedScanlators(manga, excludedScanlators)
        updateManga.awaitUpdateFetchInterval(manga, timeZone, now, currentFetchWindow)
        return manga
    }

    /**
     * Restores the categories a manga is in.
     *
     * @param manga the manga whose categories have to be restored.
     * @param categories the categories to restore.
     */
    private suspend fun restoreCategories(
        manga: Manga,
        categories: List<Long>,
        backupCategories: List<BackupCategory>,
        incomingOwnsCategories: Boolean,
    ) {
        val dbCategories = getCategories.await()
        val dbCategoriesByName = dbCategories.associateBy { it.name }

        val backupCategoriesByOrder = backupCategories.associateBy { it.order }

        val mangaCategoriesToUpdate = categories.mapNotNull { backupCategoryOrder ->
            backupCategoriesByOrder[backupCategoryOrder]?.let { backupCategory ->
                dbCategoriesByName[backupCategory.name]?.let { dbCategory ->
                    Pair(manga.id, dbCategory.id)
                }
            }
        }

        // An empty incoming list is ambiguous: in a restore it usually means the backup simply did
        // not carry categories, so leaving the local ones alone is the safe reading. In a sync from
        // the device holding the newer state it genuinely means "no longer in any category", and
        // applying it is the only way that removal can propagate.
        val shouldClearExisting = isSync && incomingOwnsCategories
        if (mangaCategoriesToUpdate.isEmpty() && !shouldClearExisting) return

        database.transaction {
            database.mangas_categoriesQueries.deleteMangaCategoryByMangaId(manga.id)
            mangaCategoriesToUpdate.forEach { (mangaId, categoryId) ->
                database.mangas_categoriesQueries.insert(mangaId, categoryId)
            }
        }
    }

    private suspend fun restoreHistory(manga: Manga, backupHistory: List<BackupHistory>) {
        val toUpdate = backupHistory.mapNotNull { history ->
            val dbHistory = database.historyQueries
                .getHistoryByChapterUrlAndMangaId(history.url, manga.id)
                .awaitAsOneOrNull()
            val item = history.getHistoryImpl()

            if (dbHistory == null) {
                val chapter = database.chaptersQueries
                    .getChapterByUrlAndMangaId(history.url, manga.id)
                    .awaitAsOneOrNull()
                return@mapNotNull if (chapter == null) {
                    // Chapter doesn't exist; skip
                    null
                } else {
                    // New history entry
                    item.copy(chapterId = chapter._id)
                }
            }

            // Update history entry
            item.copy(
                id = dbHistory._id,
                chapterId = dbHistory.chapter_id,
                readAt = max(item.readAt?.time ?: 0L, dbHistory.last_read?.time ?: 0L)
                    .takeIf { it > 0L }
                    ?.let { Date(it) },
                readDuration = max(item.readDuration, dbHistory.time_read) - dbHistory.time_read,
            )
        }

        if (toUpdate.isEmpty()) return
        database.transaction {
            toUpdate.forEach {
                database.historyQueries.upsert(
                    it.chapterId,
                    it.readAt,
                    it.readDuration,
                )
            }
        }
    }

    private suspend fun restoreTracking(manga: Manga, backupTracks: List<BackupTracking>) {
        val dbTrackByTrackerId = getTracks.await(manga.id).associateBy { it.trackerId }

        val (existingTracks, newTracks) = backupTracks
            .mapNotNull {
                val track = it.getTrackImpl()
                val dbTrack = dbTrackByTrackerId[track.trackerId]
                    ?: // New track
                    return@mapNotNull track.copy(
                        id = 0, // Let DB assign new ID
                        mangaId = manga.id,
                    )

                if (track.forComparison() == dbTrack.forComparison()) {
                    // Same state; skip
                    return@mapNotNull null
                }

                // Update to an existing track
                dbTrack.copy(
                    remoteId = track.remoteId,
                    libraryId = track.libraryId,
                    lastChapterRead = max(dbTrack.lastChapterRead, track.lastChapterRead),
                )
            }
            .partition { it.id > 0 }

        if (newTracks.isNotEmpty()) {
            insertTrack.awaitAll(newTracks)
        }

        if (existingTracks.isEmpty()) return
        database.transaction {
            existingTracks.forEach { track ->
                database.manga_syncQueries.update(
                    track.mangaId,
                    track.trackerId,
                    track.remoteId,
                    track.libraryId,
                    track.title,
                    track.lastChapterRead,
                    track.totalChapters,
                    track.status,
                    track.score,
                    track.remoteUrl,
                    track.startDate,
                    track.finishDate,
                    track.private,
                    track.id,
                )
            }
        }
    }

    private fun Track.forComparison() = this.copy(id = 0L, mangaId = 0L)

    /**
     * Restores the excluded scanlators for the manga.
     *
     * @param manga the manga whose excluded scanlators have to be restored.
     * @param excludedScanlators the excluded scanlators to restore.
     */
    private suspend fun restoreExcludedScanlators(manga: Manga, excludedScanlators: List<String>) {
        if (excludedScanlators.isEmpty()) return
        val existingExcludedScanlators = database.excluded_scanlatorsQueries
            .getExcludedScanlatorsByMangaId(manga.id)
            .awaitAsList()
        val toInsert = excludedScanlators.filter { it !in existingExcludedScanlators }
        if (toInsert.isEmpty()) return
        toInsert.forEach { database.excluded_scanlatorsQueries.insert(manga.id, it) }
    }
}
