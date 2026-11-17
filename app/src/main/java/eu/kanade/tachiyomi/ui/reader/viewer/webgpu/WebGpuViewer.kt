package eu.kanade.tachiyomi.ui.reader.viewer.webgpu

import android.content.Context
import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import ca.mpreg.imagedecoder.ImageDecoder
import ca.mpreg.webgpuviewer.closeTo
import ca.mpreg.webgpuviewer.renderer.GainmapInput
import ca.mpreg.webgpuviewer.renderer.Image
import ca.mpreg.webgpuviewer.renderer.ImageFilterWgsl
import ca.mpreg.webgpuviewer.renderer.Sampler
import ca.mpreg.webgpuviewer.transition.TransitionBasic
import ca.mpreg.webgpuviewer.transition.TransitionCube
import ca.mpreg.webgpuviewer.transition.TransitionCubeOuter
import ca.mpreg.webgpuviewer.transition.TransitionFade
import ca.mpreg.webgpuviewer.transition.TransitionFadeWhite
import ca.mpreg.webgpuviewer.transition.TransitionFlip
import ca.mpreg.webgpuviewer.transition.TransitionFlipLeft
import ca.mpreg.webgpuviewer.transition.TransitionFlipRight
import ca.mpreg.webgpuviewer.transition.TransitionNone
import ca.mpreg.webgpuviewer.transition.TransitionSphere
import ca.mpreg.webgpuviewer.transition.TransitionStackDown
import ca.mpreg.webgpuviewer.transition.TransitionStackLeft
import ca.mpreg.webgpuviewer.transition.TransitionStackRight
import ca.mpreg.webgpuviewer.transition.TransitionStackUp
import ca.mpreg.webgpuviewer.viewer.AnimationFrame
import ca.mpreg.webgpuviewer.viewer.ImagePage
import ca.mpreg.webgpuviewer.viewer.ImageViewer
import ca.mpreg.webgpuviewer.viewer.ImageViewerContinuousState
import ca.mpreg.webgpuviewer.viewer.ImageViewerState
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.model.ViewerChapters
import eu.kanade.tachiyomi.ui.reader.setting.GeneralSettings
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences.TransitionAnimation
import eu.kanade.tachiyomi.ui.reader.viewer.LongStripSettings
import eu.kanade.tachiyomi.ui.reader.viewer.PagedSettings
import eu.kanade.tachiyomi.ui.reader.viewer.ReaderPageImageView.ZoomStartPosition
import eu.kanade.tachiyomi.ui.reader.viewer.Viewer
import eu.kanade.tachiyomi.ui.reader.viewer.collectChanges
import eu.kanade.tachiyomi.ui.reader.viewer.mouseWheelPaging
import eu.kanade.tachiyomi.ui.reader.viewer.position
import eu.kanade.tachiyomi.util.system.createReaderThemeContext
import eu.kanade.tachiyomi.util.system.readerBackgroundColor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import java.util.TreeSet
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.min
import kotlin.time.Duration.Companion.milliseconds

private const val RETUNE_DELAY_MS = 150L

open class WebGpuViewer(
    val activity: ReaderActivity,
    val isReversed: Boolean,
    val isVertical: Boolean,
    val viewerState: ImageViewerState = ImageViewerState(isVertical = isVertical, isReversed = isReversed),
) : Viewer {

    open val isContinuous: Boolean = false

    /** Resolved once: render() asks per frame, and createReaderThemeContext builds a Resources. */
    @Volatile
    private var cachedBackgroundColor: Int? = null

    internal fun readerBackgroundColor(): Int =
        cachedBackgroundColor ?: activity.baseContext.readerBackgroundColor(general.theme)
            .also { cachedBackgroundColor = it }

    @Volatile
    private var cachedThemedContext: Context? = null

    /** The reader's night mode rather than the app's - see [ReaderComposePage]. */
    internal val readerThemedContext: Context
        get() = cachedThemedContext ?: activity.createReaderThemeContext().also { cachedThemedContext = it }

    protected val scope = MainScope()

    // Dedicated thread for decode worker to avoid blocking Dispatchers.Default pool
    private val decodeExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "WebGpuViewer-Decode").apply { isDaemon = true }
    }
    private val decodeDispatcher = decodeExecutor.asCoroutineDispatcher()

    // Animations decode as they play, a few at a time, apart from page decodes.
    private val animationDispatcher = Dispatchers.Default.limitedParallelism(2)

    // Guards pageCache, decodeQueue, deferredCleanup and chapterPreloadsInFlight.
    private val lock = Object()

    /** Without it the worker parks in [lock].wait() after [destroy], keeping the activity alive. */
    @Volatile
    private var destroyed = false

    private val pageCache = LinkedHashMap<PageKey, ViewerPage>()

    // Processed LIFO - last added is highest priority.
    private val decodeQueue = ArrayDeque<ViewerReaderPage>()

    /**
     * Indices of the pages that take a spread to themselves, by chapter - see [spreadStartIndex].
     * Outlives [pageCache]: every page after one of these depends on it, long since evicted.
     */
    private val loneIndices = HashMap<Long?, TreeSet<Int>>()

    /** Chapters [preloadChapterThenRetry] is already waiting on, by id. */
    private val chapterPreloadsInFlight = HashSet<Long?>()

    /**
     * Which side of a dual-page spread a [ViewerReaderPage] belongs on - app-level bookkeeping
     * for [getSpreadAnchor]/[buildSpreadPage], independent of the decoded image itself.
     */
    internal enum class SpreadPosition { LEFT, RIGHT, SINGLE }

    /** Above this, an untagged page is a spread already, not half of one. */
    private val wideAspect = 1.2f

    /** How far two untagged pages' aspect ratios may differ and still pair. */
    private val pairAspectTolerance = 0.1f

    private sealed class PageKey {
        data class Reader(val chapterId: Long?, val index: Int) : PageKey()
        data class Transition(val prevId: Long?, val nextId: Long?) : PageKey()
    }

    private fun pageKey(page: ViewerPage): PageKey = when (page) {
        is ViewerReaderPage -> PageKey.Reader(page.page.chapter.chapter.id, page.page.index)
        is ViewerTransitionPage -> PageKey.Transition(page.prevChapter?.chapter?.id, page.nextChapter?.chapter?.id)
        else -> PageKey.Transition(null, null)
    }

    private fun findInCache(key: PageKey): ViewerPage? = pageCache[key]

    protected fun viewerPageFor(imagePage: ImagePage): ViewerPage? = synchronized(lock) {
        pageCache.values.firstOrNull { it.imagePage === imagePage }
    }

    /** Check if a page is in the cache by identity. O(1) via key lookup. */
    private fun pageInCache(page: ViewerPage): Boolean = pageCache[pageKey(page)] === page

    /**
     * Queue a page for decoding if not already queued/decoding/decoded.
     * If prioritize=true and page is already queued, moves it to front.
     * Must be called while holding lock.
     */
    private fun queueForDecode(page: ViewerReaderPage, prioritize: Boolean = false) {
        // Already has a decoded image
        if (page.isDecoded) return

        when (page.state) {
            PageState.IDLE -> {
                page.state = PageState.QUEUED
                if (prioritize) {
                    decodeQueue.addLast(page)
                } else {
                    decodeQueue.addFirst(page)
                }
                lock.notify()
            }

            PageState.QUEUED -> {
                if (prioritize && decodeQueue.remove(page)) {
                    decodeQueue.addLast(page)
                }
            }

            PageState.DECODING -> {}
        }
    }

    init {
        scope.launch(decodeDispatcher) {
            try {
                while (!destroyed) {
                    // Popped, vetted and marked under one acquisition - no window for an eviction.
                    val page = synchronized(lock) {
                        while (decodeQueue.isEmpty()) {
                            if (destroyed) return@launch
                            lock.wait()
                        }
                        val candidate = decodeQueue.removeLast()
                        if (pageInCache(candidate) && !candidate.isDecoded) {
                            candidate.apply { state = PageState.DECODING }
                        } else {
                            if (pageInCache(candidate)) candidate.state = PageState.IDLE
                            null
                        }
                    } ?: continue

                    try {
                        decodeReaderPage(page)
                    } catch (e: CancellationException) {
                        // Caught below, the loop would park in wait() on a dead scope.
                        throw e
                    } catch (e: Throwable) {
                        if (e !is Exception && e !is OutOfMemoryError) throw e
                        logcat(LogPriority.ERROR, e) { "decodeReaderPage: ${e.message}" }
                        synchronized(lock) {
                            if (pageInCache(page) && !page.isDecoded && !page.imagePage.destroyed) {
                                val oldImagePage = page.imagePage
                                page.imagePage = ErrorPage(this@WebGpuViewer, page, e)
                                page.state = PageState.IDLE
                                cleanupImage(oldImagePage)
                                page.imagePage.invalidate()
                            } else {
                                if (pageInCache(page)) page.state = PageState.IDLE
                            }
                        }
                    }
                }
            } catch (_: InterruptedException) {
                // destroy()'s shutdownNow, out of lock.wait().
            } catch (_: CancellationException) {
                // Scope cancelled with the viewer.
            } catch (e: Exception) {
                logcat(LogPriority.ERROR, e) { "Decode worker died" }
            }
        }
    }

    private val settingsViewModel = activity.settingsViewModel

    /**
     * Settings every paged viewer uses, like scale mode on images or tap zones, which the long strip uses too.
     */
    val paged: PagedSettings
        get() = settingsViewModel.paged.value

    /**
     * Settings every long strip viewer uses, like crop image borders.
     */
    val longStrip: LongStripSettings
        get() = settingsViewModel.longStrip.value

    /**
     * Settings only the paged WebGPU viewers use, like page transitions, which the long strip uses too.
     */
    val webgpuPaged: WebGpuPagedSettings
        get() = settingsViewModel.webgpuPaged.value

    /**
     * Settings only the long strip WebGPU viewer uses, like the page gap.
     */
    val webgpuLongStrip: WebGpuLongStripSettings
        get() = settingsViewModel.webgpuLongStrip.value

    /**
     * Settings shared by every viewer, like allow taps.
     */
    val general: GeneralSettings
        get() = settingsViewModel.general.value

    val imageZoomType: ZoomStartPosition
        get() = paged.page.zoomStart.position(
            auto = if (isReversed) ZoomStartPosition.RIGHT else ZoomStartPosition.LEFT,
        )

    // Read from the render and decode threads, via the prevChapter/nextChapter getters.
    @Volatile
    var viewerChapters: ViewerChapters? = null

    val pages: List<ReaderPage>? get() = (currentPage as? ViewerReaderPage)?.page?.chapter?.pages

    @Volatile
    var currentPage: ViewerPage? = null

    /**
     * What a running page turn animates away from, kept out of [evictFarthestPage]'s reach - a
     * jump preloads enough pages to evict it. Replaced by the next turn's rather than cleared.
     */
    @Volatile
    private var pinnedFromPage: ImagePage? = null

    /** True while [pinnedFromPage] is drawing [image], as itself or as a spread side. */
    private fun isPinnedImage(image: ImagePage): Boolean {
        val pinned = pinnedFromPage ?: return false
        if (pinned === image) return true
        return pinned is ImagePage.ImageSpread && (pinned.left === image || pinned.right === image)
    }

    /** True while [pinnedFromPage] is drawing [page]'s image, as itself or as a spread side. */
    private fun isPinned(page: ViewerPage): Boolean = isPinnedImage(page.imagePage)

    /** Images swapped out while [pinnedFromPage] was still drawing them, i.e. mid page turn. */
    private val deferredCleanup = mutableListOf<ImagePage>()

    /** Cleans up [image], or defers it while [pinnedFromPage] draws it. Call under [lock]. */
    private fun cleanupImage(image: ImagePage) {
        if (isPinnedImage(image)) deferredCleanup.add(image) else image.cleanup()
    }

    /** Releases what [pinnedFromPage] no longer protects. Call under [lock]. */
    private fun flushDeferredCleanup() {
        val iterator = deferredCleanup.iterator()
        while (iterator.hasNext()) {
            val image = iterator.next()
            if (!isPinnedImage(image)) {
                image.cleanup()
                iterator.remove()
            }
        }
    }

    open val preloadAhead = 3
    open val preloadBehind = 2

    /**
     * Everything [preloadPages] reaches, plus slack. Sized exactly, a chapter transition page - or
     * in dual mode a spread partner - evicts a page the next fetch asks for, and it decodes again.
     */
    open val cacheSize get() = 1 + preloadAhead + preloadBehind + if (isDualPageMode()) 3 else 1

    enum class PageState {
        IDLE,
        QUEUED,
        DECODING,
    }

    /**
     * Evicts the page farthest from reference. Must be called while holding lock.
     *
     * Never evicts [reference], [keep], [currentPage] or what [pinnedFromPage] draws. Returns false
     * when nothing was evictable, so a trim loop stops instead of spinning.
     *
     * @param reference The page to use as reference (defaults to currentPage)
     * @param keep A page about to be handed out: evicted, it would be drawn destroyed.
     */
    private fun evictFarthestPage(reference: ViewerPage? = null, keep: ViewerPage? = null): Boolean {
        val current = reference ?: currentPage ?: return false
        val candidates = pageCache.values
            .filter { it !== current && it !== keep && it !== currentPage && !isPinned(it) }
            .toMutableSet()
        if (candidates.isEmpty()) return false

        // Read once - the getter measures the viewport.
        val reach = cacheSize

        fun findNext(page: ViewerPage): ViewerPage? = when (page) {
            is ViewerReaderPage -> {
                val chapterId = page.page.chapter.chapter.id
                val nextIndex = page.page.index + 1
                candidates.find {
                    it is ViewerReaderPage && it.page.chapter.chapter.id == chapterId && it.page.index == nextIndex
                } ?: candidates.find { it is ViewerTransitionPage && it.prevChapter?.chapter?.id == chapterId }
                    ?: page.nextChapter?.chapter?.id?.let { nextChapterId ->
                        candidates.find {
                            it is ViewerReaderPage && it.page.chapter.chapter.id == nextChapterId && it.page.index == 0
                        }
                    }
            }

            is ViewerTransitionPage -> {
                val nextChapterId = page.nextChapter?.chapter?.id
                candidates.find {
                    it is ViewerReaderPage && it.page.chapter.chapter.id == nextChapterId && it.page.index == 0
                }
            }

            else -> null
        }

        fun findPrev(page: ViewerPage): ViewerPage? = when (page) {
            is ViewerReaderPage -> {
                val chapterId = page.page.chapter.chapter.id
                val prevIndex = page.page.index - 1
                candidates.find {
                    it is ViewerReaderPage && it.page.chapter.chapter.id == chapterId && it.page.index == prevIndex
                } ?: candidates.find { it is ViewerTransitionPage && it.nextChapter?.chapter?.id == chapterId }
                    ?: page.prevChapter?.let { prevChapter ->
                        prevChapter.pages?.lastIndex?.let { lastIndex ->
                            candidates.find {
                                it is ViewerReaderPage && it.page.chapter.chapter.id == prevChapter.chapter.id &&
                                    it.page.index == lastIndex
                            }
                        }
                    }
            }

            is ViewerTransitionPage -> {
                val prevChapterId = page.prevChapter?.chapter?.id
                page.prevChapter?.pages?.lastIndex?.let { lastIndex ->
                    candidates.find {
                        it is ViewerReaderPage && it.page.chapter.chapter.id == prevChapterId &&
                            it.page.index == lastIndex
                    }
                }
            }

            else -> null
        }

        var farthest: ViewerPage? = null
        var forward: ViewerPage? = current
        var backward: ViewerPage? = current

        for (i in 0 until reach) {
            if (candidates.isEmpty()) break
            forward = forward?.let { findNext(it) }
            backward = backward?.let { findPrev(it) }
            if (forward == null && backward == null) break
            if (forward != null && candidates.remove(forward)) farthest = forward
            if (backward != null && candidates.remove(backward)) farthest = backward
        }

        val toRemove = candidates.firstOrNull() ?: farthest ?: return false

        pageCache.remove(pageKey(toRemove))
        decodeQueue.remove(toRemove)
        toRemove.state = PageState.IDLE
        // Through the pin: a decode that swapped this page's image leaves its spread unguarded.
        (toRemove as? ViewerReaderPage)?.spreadPage?.let(::cleanupImage)
        cleanupImage(toRemove.imagePage)
        return true
    }

    /**
     * Gets or creates a page. Thread-safe.
     * @param referencePage The page to use as reference for eviction (defaults to currentPage)
     */
    fun getPage(page: ReaderPage, referencePage: ViewerPage? = null): ViewerPage {
        val key = PageKey.Reader(page.chapter.chapter.id, page.index)
        return synchronized(lock) {
            findInCache(key) ?: ViewerReaderPage(page).also { newPage ->
                pageCache[key] = newPage
                val limit = cacheSize
                while (pageCache.size > limit) {
                    if (!evictFarthestPage(referencePage ?: newPage, keep = newPage)) break
                }
            }
        }
    }

    fun getPage(
        prevChapter: ReaderChapter?,
        nextChapter: ReaderChapter?,
        referencePage: ViewerPage? = null,
    ): ViewerPage {
        val key = PageKey.Transition(prevChapter?.chapter?.id, nextChapter?.chapter?.id)
        return synchronized(lock) {
            findInCache(key) ?: ViewerTransitionPage(prevChapter, nextChapter).also { newPage ->
                pageCache[key] = newPage
                val limit = cacheSize
                while (pageCache.size > limit) {
                    if (!evictFarthestPage(referencePage ?: newPage, keep = newPage)) break
                }
            }
        }
    }

    /**
     * Kicks off loading [chapter] and, once its pages actually show up, re-runs
     * [preloadPages] from the current page - [ReaderActivity]'s viewModel.preload isn't
     * guaranteed to have finished loading by the time it returns, so a single immediate
     * retry can race it and silently never queue the adjacent chapter's edge page for
     * decode. Gives up after 5 seconds if the chapter never finishes loading.
     */
    private fun preloadChapterThenRetry(chapter: ReaderChapter) {
        // fetchPage reaches prev/next per frame - unguarded, each frame starts another 5s poll.
        val chapterId = chapter.chapter.id
        synchronized(lock) {
            if (!chapterPreloadsInFlight.add(chapterId)) return
        }

        scope.launch(Dispatchers.IO) {
            try {
                activity.viewModel.preload(chapter)
                repeat(25) {
                    if (chapter.state is ReaderChapter.State.Loaded) {
                        currentPage?.let { preloadPages(it) }
                        return@launch
                    }
                    delay(200.milliseconds)
                }
            } finally {
                synchronized(lock) { chapterPreloadsInFlight.remove(chapterId) }
            }
        }
    }

    abstract class ViewerPage {
        abstract val prevChapter: ReaderChapter?
        abstract val nextChapter: ReaderChapter?
        abstract val prev: ViewerPage?
        abstract val next: ViewerPage?

        @Volatile
        var state: PageState = PageState.IDLE

        @Volatile
        open var imagePage: ImagePage = ImagePage.Dummy(400, 400)

        open val isDecoded = true
    }

    inner class ViewerTransitionPage(
        override val prevChapter: ReaderChapter?,
        override val nextChapter: ReaderChapter?,
    ) : ViewerPage() {
        override var imagePage: ImagePage = TransitionPage(this@WebGpuViewer, prevChapter, nextChapter)

        override val prev: ViewerPage?
            get() = prevChapter?.pages?.lastOrNull()?.let { getPage(it, currentPage) }

        override val next: ViewerPage?
            get() = nextChapter?.pages?.firstOrNull()?.let { getPage(it, currentPage) }
    }

    inner class ViewerReaderPage(val page: ReaderPage) : ViewerPage() {
        /** Cached spread ImagePage when this page is the anchor of a dual-page spread */
        var spreadPage: ImagePage.ImageSpread? = null

        /** The side the file names, or null for none. Never a value merely derived from the index. */
        @Volatile
        internal var taggedSpreadPosition: SpreadPosition? = null

        /** The decoded image's shape, or null while this page is still a placeholder. */
        internal val aspectRatio: Float?
            get() = (imagePage as? ImagePage.ImageSingle)?.takeIf { it !is ImagePage.Compose }?.let {
                val height = it.trimHeight
                if (it.isDecoded && height > 0) it.trimWidth.toFloat() / height else null
            }

        /**
         * Which half of a spread this page is on - derived until the file tags it. Without that a
         * still-loading page stays SINGLE, never pairs, and its ring draws mid-screen; deriving it
         * live also re-decides it on a rotation in or out of dual mode.
         *
         * Untagged goes by [wideAspect] first, then [derivedSpreadPosition].
         */
        internal val spreadPosition: SpreadPosition
            get() {
                taggedSpreadPosition?.let { return it }
                if (standsAlone) return SpreadPosition.SINGLE
                return derivedSpreadPosition(page)
            }

        /** True when nothing may share this page's spread - it is one already. */
        internal val standsAlone: Boolean
            get() = taggedSpreadPosition == SpreadPosition.SINGLE || (aspectRatio ?: 0f) > wideAspect

        override var imagePage: ImagePage = ProgressPage(this@WebGpuViewer)

        /** The reader's chrome doesn't count, though it is always "decoded". */
        override val isDecoded
            get() = imagePage.let {
                it !is ImagePage.Compose && (it as? ImagePage.ImageSingle)?.isDecoded == true
            }

        override val prevChapter: ReaderChapter?
            get() = when (page.chapter) {
                viewerChapters?.currChapter -> viewerChapters?.prevChapter
                viewerChapters?.nextChapter -> viewerChapters?.currChapter
                else -> null
            }

        override val nextChapter: ReaderChapter?
            get() = when (page.chapter) {
                viewerChapters?.currChapter -> viewerChapters?.nextChapter
                viewerChapters?.prevChapter -> viewerChapters?.currChapter
                else -> null
            }

        override val prev: ViewerPage?
            get() = page.chapter.pages?.let { pages ->
                pages.getOrNull(page.index - 1)?.let { getPage(it, currentPage) } ?: run {
                    val prevChapter = prevChapter ?: return@run getPage(null, page.chapter, currentPage)

                    if (prevChapter.state !is ReaderChapter.State.Loaded) {
                        preloadChapterThenRetry(prevChapter)
                    }

                    if (general.alwaysShowChapterTransition) {
                        getPage(prevChapter, page.chapter, currentPage)
                    } else {
                        prevChapter.pages?.lastOrNull()?.let { getPage(it, currentPage) }
                    }
                }
            }

        override val next: ViewerPage?
            get() = page.chapter.pages?.let { pages ->
                pages.getOrNull(page.index + 1)?.let { getPage(it, currentPage) } ?: run {
                    val nextChapter = nextChapter ?: return@run getPage(page.chapter, null, currentPage)

                    if (nextChapter.state !is ReaderChapter.State.Loaded) {
                        preloadChapterThenRetry(nextChapter)
                    }

                    if (general.alwaysShowChapterTransition) {
                        getPage(page.chapter, nextChapter, currentPage)
                    } else {
                        nextChapter.pages?.firstOrNull()?.let { getPage(it, currentPage) }
                    }
                }
            }
    }

    /**
     * Check if dual page mode is currently active based on settings and view dimensions.
     * Dual page is never active for continuous (scrolling) viewers.
     */
    fun isDualPageMode(): Boolean {
        if (isContinuous) return false
        return when (webgpuPaged.page.dualPageView) {
            ReaderPreferences.DualPageView.NEVER -> false
            ReaderPreferences.DualPageView.ALWAYS -> true
            ReaderPreferences.DualPageView.WIDE -> {
                val width = viewerState.width
                val height = viewerState.height
                width > 0 && height > 0 && width.toFloat() / height > 1f
            }
        }
    }

    /** The half a spread opens on: right reading right-to-left, left otherwise. */
    private val anchorPosition get() = if (isReversed) SpreadPosition.RIGHT else SpreadPosition.LEFT

    private val partnerPosition get() = if (isReversed) SpreadPosition.LEFT else SpreadPosition.RIGHT

    /**
     * Which half a page falls on when nothing tags the file: alternating from its spread's start,
     * anchor then partner. SINGLE outside dual page mode, so nothing pairs while one page fills
     * the viewer.
     */
    private fun derivedSpreadPosition(page: ReaderPage): SpreadPosition {
        if (!isDualPageMode()) return SpreadPosition.SINGLE
        val offset = page.index - spreadStartIndex(page.chapter.chapter.id, page.index)
        return if (offset >= 0 && offset % 2 == 0) anchorPosition else partnerPosition
    }

    /**
     * Where the spread holding [index] starts: just past the last page before it that took one to
     * itself, so the page after a detected spread opens the next one instead of inheriting a parity
     * that page broke. Defaults to 1 - page 0 is the cover, and pairs with nothing.
     */
    private fun spreadStartIndex(chapterId: Long?, index: Int): Int {
        val lone = synchronized(lock) { loneIndices[chapterId]?.lower(index) } ?: return 1
        return lone + 1
    }

    /** Registers whether [page] stands alone, for [spreadStartIndex]. Must hold [lock]. */
    private fun noteIfLone(page: ViewerReaderPage) {
        val indices = loneIndices.getOrPut(page.page.chapter.chapter.id) { TreeSet() }
        if (page.standsAlone) indices.add(page.page.index) else indices.remove(page.page.index)
    }

    /**
     * Whether these two may share a spread, beyond their positions agreeing. Both tagged is taken
     * as read; a pair resting on page order needs the same shape - halves of one sheet scan alike.
     * Undecoded pairs anyway, or a loading page draws its ring mid-screen.
     */
    private fun canPairShapes(anchor: ViewerReaderPage, partner: ViewerReaderPage): Boolean {
        if (anchor.taggedSpreadPosition != null && partner.taggedSpreadPosition != null) return true
        val a = anchor.aspectRatio ?: return true
        val b = partner.aspectRatio ?: return true
        return abs(a - b) <= pairAspectTolerance
    }

    /**
     * Get the anchor page for a spread.
     * RTL: anchor is RIGHT, for LEFT page returns previous RIGHT
     * LTR: anchor is LEFT, for RIGHT page returns previous LEFT
     */
    private fun getSpreadAnchor(page: ViewerPage): ViewerPage {
        if (!isDualPageMode()) return page
        if (page !is ViewerReaderPage) return page

        if (page.spreadPosition == partnerPosition) {
            val prev = page.prev as? ViewerReaderPage ?: return page
            if (prev.page.chapter == page.page.chapter && prev.spreadPosition == anchorPosition &&
                canPairShapes(prev, page)
            ) {
                return prev
            }
        }

        return page
    }

    /** Who [page] pairs with, or null. One verdict for [buildSpreadPage] and [progressPage]. */
    private fun spreadPartner(page: ViewerReaderPage): ViewerReaderPage? {
        if (!isDualPageMode()) return null
        if (page.spreadPosition != anchorPosition) return null
        val next = (page.next as? ViewerReaderPage)?.takeIf { it.page.chapter == page.page.chapter } ?: return null
        return next.takeIf { it.spreadPosition == partnerPosition && canPairShapes(page, it) }
    }

    /** Page to report progress for - the spread's lastmost page, not the anchor. */
    private fun progressPage(page: ViewerPage): ViewerReaderPage? {
        val readerPage = page as? ViewerReaderPage ?: return null
        return spreadPartner(readerPage) ?: readerPage
    }

    private fun buildSpreadPage(page: ViewerPage): ImagePage {
        if (page !is ViewerReaderPage) {
            return page.imagePage
        }

        if (!isDualPageMode()) {
            return page.imagePage
        }

        // Whatever the page is holding takes its half of the seam, decoded or not:
        // [ImagePage.ImageSpread] draws a [ImagePage.Render] side into its own half. A page left
        // out would take the whole viewport instead, hiding its partner with it.
        val imagePage = page.imagePage

        if (page.spreadPosition == SpreadPosition.SINGLE) {
            page.spreadPage = null
            return imagePage
        }

        // Null for a partner reaching here directly, which means no anchor before it - a lone
        // RIGHT at a chapter boundary - so it draws alone on its own side.
        val partnerImagePage = spreadPartner(page)?.imagePage

        // LEFT/RIGHT map directly to the spread's left/right slot - independent of reading
        // direction, which only decides which side is the anchor for pairing purposes above.
        val left = if (page.spreadPosition == SpreadPosition.LEFT) imagePage else partnerImagePage
        val right = if (page.spreadPosition == SpreadPosition.RIGHT) imagePage else partnerImagePage

        // Reuse existing spread if the sides match - preserves transform state
        val existing = page.spreadPage
        if (existing != null && existing.left === left && existing.right === right) {
            return existing
        }

        // Create new spread. Composes the existing page(s) directly, so either side (or both)
        // keeps animating independently via its own already-running frame loop - no copying of
        // animation state needed. The other slot is simply null when there's no partner (yet).
        val spread = ImagePage.ImageSpread(left, right)
        page.spreadPage = spread
        return spread
    }

    init {
        viewerState.apply {
            fetchPage = fetch@{ index ->
                val current = currentPage ?: return@fetch null

                // For index 0, return the current spread
                if (index == 0) {
                    return@fetch buildSpreadPage(getSpreadAnchor(current))
                }

                // Navigate by spreads from current
                var page = current
                val step = if (index > 0) 1 else -1
                repeat(abs(index)) {
                    page = nextPage(page, step) ?: return@fetch null
                }

                return@fetch buildSpreadPage(page)
            }

            onTap = { offset ->
                activity.onViewerTap(offset)
            }

            onLongTap = { _ ->
                if (activity.viewModel.state.value.menuVisible || general.longTapEnabled) {
                    (currentPage as? ViewerReaderPage)?.let { activity.onPageLongTap(it.page) }
                }
            }
        }

        // The pages are made with all of these, applied at first only from the WebGPU paged ones every mode reads
        settingsViewModel.general.collectChanges(scope) { old, new ->
            if (old != null && old.theme != new.theme) {
                onPageSettingsChanged()
            }
        }
        settingsViewModel.webgpuPaged.collectChanges(scope) { old, new ->
            if (old?.page != new.page) {
                onPageSettingsChanged()
            }
        }
        if (isContinuous) {
            settingsViewModel.longStrip.collectChanges(scope) { old, _ ->
                if (old != null) {
                    onPageSettingsChanged()
                }
            }
            settingsViewModel.webgpuLongStrip.collectChanges(scope) { old, new ->
                if (old != null && old.page != new.page) {
                    onPageSettingsChanged()
                }
            }
        }
        val common = if (isContinuous) {
            settingsViewModel.webgpuLongStrip.map { it.common }
        } else {
            settingsViewModel.webgpuPaged.map { it.common }
        }
        common.distinctUntilChanged().collectChanges(scope) { old, new ->
            if (old?.doubleTapGesturesDisabled != new.doubleTapGesturesDisabled) {
                viewerState.doubleTapGesturesEnabled = !new.doubleTapGesturesDisabled
            }
            if (old?.fastRenderingOnly != new.fastRenderingOnly) {
                viewerState.enableTiles = !new.fastRenderingOnly
            }
            if (old?.holdUntilFinal != new.holdUntilFinal) viewerState.holdUntilFinal = new.holdUntilFinal
            if (old?.imageFilters != new.imageFilters) imageFilters.update(new.imageFilters)
            if (old?.tileFilters != new.tileFilters) tileFilters.update(new.tileFilters)
            if (old?.upscaler != new.upscaler) upscaler.update(new.upscaler)
            if (old?.downscaler != new.downscaler) downscaler.update(new.downscaler)
        }
        settingsViewModel.paged.collectChanges(scope) { old, new ->
            if (old != null && old.page != new.page) {
                onPageSettingsChanged()
            }
        }
    }

    /**
     * Applies what the pages are made with and remakes them.
     */
    private fun onPageSettingsChanged() {
        val pageSettings = webgpuPaged.page
        // A theme change comes through here.
        cachedBackgroundColor = null
        cachedThemedContext = null

        val isDual = isDualPageMode()
        viewerState.apply {
            transition = when (if (isDual) pageSettings.transitionAnimationDual else pageSettings.transitionAnimation) {
                TransitionAnimation.BASIC -> if (isVertical) TransitionBasic.Vertical else TransitionBasic
                TransitionAnimation.FLIP -> TransitionFlip
                TransitionAnimation.FLIP_LEFT -> TransitionFlipLeft
                TransitionAnimation.FLIP_RIGHT -> TransitionFlipRight
                TransitionAnimation.STACK_LEFT -> TransitionStackLeft
                TransitionAnimation.STACK_RIGHT -> TransitionStackRight
                TransitionAnimation.STACK_UP -> TransitionStackUp
                TransitionAnimation.STACK_DOWN -> TransitionStackDown
                TransitionAnimation.SPHERE -> TransitionSphere
                TransitionAnimation.CUBE_INSIDE -> TransitionCube
                TransitionAnimation.CUBE_OUTSIDE -> TransitionCubeOuter
                TransitionAnimation.FADE -> TransitionFade
                TransitionAnimation.FADE_WHITE -> TransitionFadeWhite
                TransitionAnimation.NONE -> TransitionNone
            }

            when (if (isDual) pageSettings.cutoutModeDual else pageSettings.cutoutMode) {
                ReaderPreferences.CutoutMode.IGNORE -> avoidCutout = false
                ReaderPreferences.CutoutMode.AVOID -> {
                    avoidCutout = true
                    alwaysAvoidCutout = false
                }

                ReaderPreferences.CutoutMode.SHIFT -> {
                    avoidCutout = true
                    alwaysAvoidCutout = true
                }
            }

            (this as? ImageViewerContinuousState)?.let {
                backgroundColor = readerBackgroundColor()
                homeScale = webgpuLongStrip.page.continuousMinWidth / 100f
                scale = homeScale
                cropBorders = this@WebGpuViewer.cropBorders
                minScale = if (longStrip.zoomOutDisabled) 0f else 0.1f

                (this@WebGpuViewer as? WebGpuViewerContinuous)?.let {
                    if (this@WebGpuViewer.useGap) {
                        pageGap = webgpuLongStrip.page.continuousGap / 100f
                    }
                }
            }
        }

        synchronized(lock) {
            decodeQueue.clear()
            pageCache.values.forEach {
                it.state = PageState.IDLE
                (it as? ViewerReaderPage)?.spreadPage?.let(::cleanupImage)
                cleanupImage(it.imagePage)
            }
            pageCache.clear()

            currentPage = (currentPage as? ViewerReaderPage)?.page?.let { getPage(it) }
                ?: (currentPage as? ViewerTransitionPage)?.let {
                    getPage(it.prevChapter, it.nextChapter)
                }

            currentPage?.let { preloadPages(it) }
        }

        viewerState.invalidate()
    }

    private val imageFilters = FilterSlot { viewerState.imageFilters = it }
    private val tileFilters = FilterSlot { viewerState.tileFilters = it }
    private val upscaler = SamplerSlot(Sampler::catmullRom) { viewerState.upscaler = it }
    private val downscaler = SamplerSlot(Sampler::box) { viewerState.downscaler = it }

    // Main thread only.
    private inner class SamplerSlot(private val default: () -> Sampler, private val assign: (Sampler) -> Unit) {
        private var job: Job? = null

        fun update(shader: WebGpuShader?) {
            // Else an older, slower load could land last.
            job?.cancel()
            job = scope.launch {
                // default() reads a resource.
                val sampler = withContext(Dispatchers.IO) {
                    shader?.let {
                        try {
                            WebGpuShaders.loadSampler(activity, it)
                        } catch (e: Exception) {
                            logcat(LogPriority.ERROR, e) { "sampler ${it.name}" }
                            null
                        }
                    } ?: default()
                }
                assign(sampler)
            }
        }
    }

    // Main thread only.
    private inner class FilterSlot(private val assign: (List<ImageFilterWgsl>) -> Unit) {
        private var job: Job? = null
        private var retune: Job? = null
        private var loaded: List<Pair<WebGpuShader, ImageFilterWgsl>> = emptyList()

        fun update(shaders: List<WebGpuShader>) {
            retune?.cancel()
            if (job?.isActive != true && loaded.map { it.first.file } == shaders.map { it.file }) {
                // Each value rebuilds pipelines on the render thread; a slider sends one per step.
                retune = scope.launch {
                    delay(RETUNE_DELAY_MS.milliseconds)
                    loaded = loaded.zip(shaders) { (_, filter), shader ->
                        WebGpuShaders.applyValues(filter, shader)
                        shader to filter
                    }
                }
                return
            }
            // Else an older, slower load could land last.
            job?.cancel()
            job = scope.launch {
                val fresh = load(shaders)
                loaded = fresh
                assign(fresh.map { it.second })
            }
        }

        /** Fresh instances: one belongs to one viewer and one chain. Skips any that fail to load. */
        private suspend fun load(shaders: List<WebGpuShader>) = withContext(Dispatchers.IO) {
            shaders.mapNotNull { shader ->
                try {
                    shader to WebGpuShaders.load(activity, shader)
                } catch (e: Exception) {
                    logcat(LogPriority.ERROR, e) { "filter ${shader.name}" }
                    null
                }
            }
        }
    }

    override fun destroy() {
        // Before the interrupt: taken mid-decode, only the flag stops the worker parking.
        destroyed = true
        scope.cancel()

        // shutdownNow interrupts the worker out of lock.wait().
        decodeExecutor.shutdownNow()
        decodeDispatcher.close()

        synchronized(lock) {
            decodeQueue.clear()
            // Nothing can still be animating, so the pin has nothing left to protect.
            pinnedFromPage = null
            pageCache.values.forEach {
                it.state = PageState.IDLE
                (it as? ViewerReaderPage)?.spreadPage?.cleanup()
                it.imagePage.cleanup()
            }
            pageCache.clear()
            deferredCleanup.forEach { it.cleanup() }
            deferredCleanup.clear()
            loneIndices.clear()
            chapterPreloadsInFlight.clear()
            lock.notifyAll()
        }
    }

    @Composable
    override fun Content(modifier: Modifier) {
        ImageViewer(modifier = modifier.mouseWheelPaging(this), state = viewerState)
    }

    private val cropBorders: Boolean
        get() = if (isContinuous) longStrip.cropBorders else paged.page.cropBorders

    /** [ErrorPage]'s Retry: back to the ring, then decoded afresh. */
    internal fun retryLoad(page: ViewerReaderPage) {
        synchronized(lock) {
            if (!pageInCache(page) || page.imagePage !is ErrorPage) return
            val old = page.imagePage
            page.imagePage = ProgressPage(this)
            cleanupImage(old)
            page.state = PageState.IDLE
            queueForDecode(page, prioritize = true)
        }
        viewerState.invalidate()
    }

    private suspend fun decodeReaderPage(page: ViewerReaderPage) {
        val stream = page.page.stream?.invoke() ?: run {
            synchronized(lock) { if (pageInCache(page)) page.state = PageState.IDLE }
            return
        }

        stream.use { input ->
            synchronized(lock) {
                if (!pageInCache(page) || page.isDecoded) {
                    if (pageInCache(page)) page.state = PageState.IDLE
                    return
                }
            }

            logcat(LogPriority.INFO) { "Decoding ${pageName(page)}" }
            val started = SystemClock.uptimeMillis()
            var done = false
            try {
                buildPage(page, ImageDecoder.open(input))
                done = true
            } finally {
                val ms = SystemClock.uptimeMillis() - started
                logcat(LogPriority.INFO) {
                    "${if (done) "Decoded" else "Failed decoding"} ${pageName(page)} in $ms ms"
                }
            }
        }
    }

    private fun pageName(page: ViewerReaderPage): String {
        val p = page.page
        return "${p.chapter.chapter.name} page ${p.index + 1}"
    }

    /**
     * Decodes [dec] into [page], then closes it - unless an animation keeps it, to decode frames
     * past the first as they come due.
     */
    private suspend fun buildPage(page: ViewerReaderPage, dec: ImageDecoder) {
        var kept = false
        try {
            decodeInto(page, dec) { kept = true }
        } finally {
            if (!kept) dec.close()
        }
    }

    private suspend fun decodeInto(page: ViewerReaderPage, dec: ImageDecoder, keep: () -> Unit) {
        // The decoder hands the map over unapplied - see ImageDecoder.Gainmap - because how
        // much of it to use depends on the display, so the viewer applies it.
        fun ImageDecoder.Gainmap.toInput() = let {
            GainmapInput(
                pixels = it.pixels,
                width = it.width,
                height = it.height,
                channels = it.channels,
                gamma = it.gamma,
                minContentBoost = it.minContentBoost,
                maxContentBoost = it.maxContentBoost,
                offsetSdr = it.offsetSdr,
                offsetHdr = it.offsetHdr,
            )
        }

        if (isDualPageMode()) {
            page.taggedSpreadPosition = when (dec.getTag("PageName")) {
                "Left" -> SpreadPosition.LEFT
                "Right" -> SpreadPosition.RIGHT
                null -> null
                else -> SpreadPosition.SINGLE
            }
        }

        val backgroundColor = if (general.automaticBackground) null else readerBackgroundColor()

        // Null once the page is unwanted.
        fun nextWhole(): ImageDecoder.Frame? {
            while (true) {
                if (!synchronized(lock) { pageInCache(page) }) return null
                val f = dec.decodeNext()
                if (!f.partial) return f
                // A partial views the decoder's canvas, valid only until the next step.
                f.close()
            }
        }

        val firstFrame = nextWhole() ?: return
        // A new image until a page takes it.
        var orphan: Image? = null
        // Whole frames hold native pixels until closed, on every exit.
        try {
            val animated = dec.hasNext
            // Image filters draw only base levels. Pages already made keep what they have.
            val mipMaps = !animated && viewerState.imageFilters.isEmpty()

            // A gain map belongs to a still; its headroom is the map's, not the decoder's.
            val gainmap = if (!animated && dec.hdrKind == ImageDecoder.HdrKind.GAINMAP) {
                dec.getGainmap()
            } else {
                null
            }
            val hdrHeadroom = gainmap?.headroomStops ?: dec.hdrHeadroom

            // Only trim when not animated and not in dual page mode
            val trimColors = if (!animated && cropBorders && !isDualPageMode()) {
                listOf(
                    floatArrayOf(1f, 1f, 1f),
                    floatArrayOf(0f, 0f, 0f),
                )
            } else {
                null
            }
            val crop = trimColors != null && !isContinuous && webgpuPaged.page.cropInsteadOfZoom

            val firstImage = Image(
                firstFrame.image,
                firstFrame.width,
                firstFrame.height,
                createMipMaps = mipMaps,
                trimColors = trimColors,
                trimThreshold = 0.15f,
                backgroundColor = backgroundColor,
                hdr = dec.isHdr,
                hdrHeadroom = hdrHeadroom,
                gainmap = gainmap?.toInput(),
            ).also {
                it.crop = crop
                orphan = it
            }
            val firstDuration = firstFrame.duration
            firstFrame.close()

            synchronized(lock) {
                val target = when {
                    // Evicted.
                    !pageInCache(page) -> null

                    // A new page in the placeholder's place.
                    !page.isDecoded && !page.imagePage.destroyed -> {
                        val imagePage = ImagePage.ImageSingle(firstImage)
                        orphan = null
                        val old = page.imagePage
                        page.imagePage = imagePage
                        cleanupImage(old)
                        // Fade up from the placeholder's colour, if that placeholder was on screen -
                        // one that decoded out of view has nothing left to fade from.
                        if (old.isOnScreen) imagePage.fadeIn()
                        if (!isDualPageMode() && !applyWideZoomIfNeeded(imagePage)) {
                            applyFitModeAnchor(imagePage)
                        }
                        imagePage
                    }

                    else -> null
                }
                if (pageInCache(page)) page.state = PageState.IDLE
                target?.also { installedPage ->
                    if (animated) {
                        // The page takes the decoder, which loops: the rest decode as they're due.
                        keep()
                        val name = pageName(page)
                        installedPage.animate(firstDuration, dec::close) {
                            withContext(animationDispatcher) {
                                val f = if (dec.hasNext) {
                                    val started = SystemClock.uptimeMillis()
                                    dec.decodeNext().also {
                                        logcat(LogPriority.DEBUG) {
                                            "Decoded frame ${it.index} of $name in " +
                                                "${SystemClock.uptimeMillis() - started} ms"
                                        }
                                    }
                                } else {
                                    null
                                }
                                f?.let { AnimationFrame(it.image, it.duration) { it.close() } }
                            }
                        }
                    }
                    noteIfLone(page)
                    viewerState.invalidate()
                }
            }
        } finally {
            firstFrame.close()
            // Nothing took it, normally or not.
            orphan?.let { ImagePage.ImageSingle(it).cleanup() }
        }
    }

    private fun applyWideZoomIfNeeded(page: ImagePage.ImageSingle): Boolean {
        if (!paged.page.landscapeZoom) return false

        val screenW = viewerState.width
        val screenH = viewerState.viewportHeight
        if (screenW <= 0 || screenH <= 0) return false

        // don't zoom if it fits at original scale
        if (page.trimWidth <= screenW) return false

        val image = page.image ?: return false

        val aspectRatio = min(
            page.trimWidth.toFloat() / page.trimHeight.toFloat(),
            image.width.toFloat() / image.height.toFloat(),
        )

        // not wide enough
        if (aspectRatio < 1.1) return false

        // Wide page: half of it is wider than the screen aspect ratio or matches a decoded neighbour's shape
        val isWide = listOf(viewerPageFor(page)?.prev, viewerPageFor(page)?.next)
            .mapNotNull { (it as? ViewerReaderPage)?.aspectRatio }
            .any { abs(aspectRatio / 2f - it) <= pairAspectTolerance } ||
            (aspectRatio > 2f * screenW.toFloat() / screenH)
        if (!isWide) return false

        page.parent = viewerState

        // Half the image width fills the screen width.
        page.homeScale = screenW.toFloat() / (page.trimWidth / 2f)

        page.scale = page.homeScale

        val minX = page.minX(page.homeScale)
        val maxX = page.maxX(page.homeScale)

        page.x = when (imageZoomType) {
            ZoomStartPosition.LEFT -> maxX
            ZoomStartPosition.RIGHT -> minX
            ZoomStartPosition.CENTER -> 0f
        }

        page.y = page.homeY

        return true
    }

    private fun applyFitModeAnchor(page: ImagePage.ImageSingle) {
        val scaleType = paged.page.imageScaleType
        if (
            scaleType != ReaderPreferences.ScaleType.FIT_WIDTH &&
            scaleType != ReaderPreferences.ScaleType.FIT_HEIGHT &&
            scaleType != ReaderPreferences.ScaleType.ORIGINAL_SIZE
        ) {
            return
        }

        val screenW = viewerState.width
        val screenH = viewerState.viewportHeight
        if (screenW <= 0 || screenH <= 0) return

        val w = page.trimWidth.toFloat()
        val h = page.trimHeight.toFloat()
        if (w <= 0f || h <= 0f) return

        page.parent = viewerState

        page.homeScale = when (scaleType) {
            ReaderPreferences.ScaleType.FIT_WIDTH -> screenW / w
            ReaderPreferences.ScaleType.FIT_HEIGHT -> screenH / h
            else -> 1f
        }.coerceAtLeast(0.01f)

        page.scale = page.homeScale

        if (scaleType == ReaderPreferences.ScaleType.ORIGINAL_SIZE) {
            if (page.homeScale < page.minScale) {
                page.minScale = page.homeScale
            }
        }

        val minX = page.minX(page.homeScale)
        val maxX = page.maxX(page.homeScale)

        page.x = when (imageZoomType) {
            ZoomStartPosition.LEFT -> maxX
            ZoomStartPosition.RIGHT -> minX
            ZoomStartPosition.CENTER -> 0f
        }

        page.y = page.homeY
    }

    protected fun preloadPage(page: ViewerPage, prioritize: Boolean = false) {
        synchronized(lock) {
            val cachedPage = findInCache(pageKey(page)) ?: return
            if (cachedPage is ViewerReaderPage) {
                queueForDecode(cachedPage, prioritize)
            }
        }
    }

    /** Drops the queued decodes outside [window]. */
    private fun resetQueues(window: Set<PageKey>) {
        synchronized(lock) {
            val stale = decodeQueue.filter { pageKey(it) !in window }
            decodeQueue.removeAll(stale.toSet())
            stale.forEach { if (it.state == PageState.QUEUED) it.state = PageState.IDLE }
        }
    }

    protected fun preloadPages(page: ViewerPage) {
        // page may be a stale copy - resolve the live cache entry.
        val key = pageKey(page)
        val cachedPage = synchronized(lock) { findInCache(key) } ?: return

        // prev, then next, then current+partner - the last prioritized call ends up highest.
        val prevPages = mutableListOf<ViewerPage>()
        var p: ViewerPage? = cachedPage
        for (i in 0 until preloadBehind) {
            p = p?.prev ?: break
            prevPages.add(p)
        }
        prevPages.asReversed().forEach { preloadPage(it) }

        val nextPages = mutableListOf<ViewerPage>()
        p = cachedPage
        for (i in 0 until preloadAhead) {
            p = p?.next ?: break
            nextPages.add(p)
        }
        resetQueues((prevPages + cachedPage + nextPages).mapTo(HashSet()) { pageKey(it) })

        nextPages.asReversed().forEach { preloadPage(it) }

        cachedPage.next?.let { preloadPage(it, prioritize = true) }
        preloadPage(cachedPage, prioritize = true)
    }

    /**
     * Tells this viewer to set the given [chapters] as active. If the pager is currently idle,
     * it sets the chapters immediately, otherwise they are saved and set when it becomes idle.
     */
    override fun setChapters(chapters: ViewerChapters) {
        // Empty too: lastIndex would be -1, and the requested page is read from it.
        val pages = chapters.currChapter.pages
        if (pages.isNullOrEmpty()) return

        this.viewerChapters = chapters

        // Only when nothing shows yet - re-setting chapters must not move the page.
        val page = currentPage ?: getPage(pages[min(chapters.currChapter.requestedPage, pages.lastIndex)])
        val anchor = getSpreadAnchor(page)
        currentPage = anchor
        progressPage(anchor)?.let { activity.onPageSelected(it.page) }
        preloadPages(anchor)

        viewerState.apply {
            onPageChange = onPageChange@{ delta ->
                // The viewer already showed the page at fetchPage(delta).
                // We need to update currentPage to match that.
                val current = currentPage ?: return@onPageChange

                // Navigate the same way fetchPage does
                var page = current
                val step = if (delta > 0) 1 else -1
                repeat(abs(delta)) {
                    page = nextPage(page, step) ?: return@onPageChange
                }

                // Synchronous, since the viewer walks getPage() from here - stale, and the next
                // scroll step crosses the same boundary again.
                currentPage = page

                // The rest ran here too, on the animation thread under the viewer's scroll lock.
                // Posted in order, so nothing is skipped or reordered - and on this viewer's own
                // MainScope, not the state's: that one dispatches inside the frame callback.
                val settled = page
                this@WebGpuViewer.scope.launch {
                    if (!isContinuous) {
                        if (!activity.isScrollingThroughPages) {
                            activity.hideMenu()
                        }
                        progressPage(settled)?.let { activity.onPageSelected(it.page) }
                    }
                    preloadPages(settled)

                    if (!isContinuous) {
                        (settled as? ViewerTransitionPage)?.let { transitionPage ->
                            if (transitionPage.prevChapter == null || transitionPage.nextChapter == null) {
                                activity.showMenu()
                            }
                        }
                    }
                }
            }

            invalidate()
        }
    }

    /**
     * Tells this viewer to move to the given [page].
     * In dual page mode, aligns to the start of the spread containing the page.
     */
    override fun moveToPage(page: ReaderPage) {
        // Pin first: resolving a target outside the cached window trims the cache.
        pinnedFromPage = currentPage?.let { buildSpreadPage(it) }
        moveToPage(getSpreadAnchor(getPage(page)))
    }

    private fun moveToPage(newPage: ViewerPage) {
        val previousPage = currentPage
        // Before preloadPages below trims the cache - see [pinnedFromPage].
        val fromSpread = previousPage?.let { buildSpreadPage(it) }
        pinnedFromPage = fromSpread
        synchronized(lock) { flushDeferredCleanup() }

        currentPage = newPage
        progressPage(newPage)?.let { activity.onPageSelected(it.page) }
        preloadPages(newPage)

        (newPage as? ViewerTransitionPage)?.let { ViewerTransitionPage ->
            if (ViewerTransitionPage.prevChapter == null || ViewerTransitionPage.nextChapter == null) {
                activity.showMenu()
            }
        }

        if (previousPage == null) return

        val direction = when (previousPage) {
            is ViewerReaderPage if newPage is ViewerReaderPage -> if (previousPage.page.chapter ==
                newPage.page.chapter
            ) {
                (newPage.page.index - previousPage.page.index).coerceIn(-1, 1)
            } else if (previousPage.page.chapter == newPage.prevChapter) {
                1
            } else {
                -1
            }

            is ViewerTransitionPage if newPage is ViewerReaderPage -> if (previousPage.nextChapter ==
                newPage.page.chapter
            ) {
                1
            } else {
                -1
            }

            is ViewerReaderPage if newPage is ViewerTransitionPage -> if (previousPage.page.chapter ==
                newPage.prevChapter
            ) {
                1
            } else {
                -1
            }

            else -> 0
        }

        if (direction != 0 && fromSpread != null) {
            animateTurn(direction, fromSpread)
        } else {
            viewerState.invalidate()
        }
    }

    /** How a [moveToPage] turn is shown. [direction] is 1 forward through the pages, -1 back. */
    protected open fun animateTurn(direction: Int, fromSpread: ImagePage) {
        viewerState.transitionFromPage = fromSpread
        viewerState.animatePageTurn(if (isReversed) direction else -direction)
    }

    override fun moveForward() = if (isReversed) moveLeft() else moveRight()

    override fun moveBackward() = if (isReversed) moveRight() else moveLeft()

    protected open fun moveRight() {
        viewerState.getPage(0)?.let { page ->
            if (paged.navigateToPan) {
                val minX = page.minX(page.scale)
                val maxX = page.maxX(page.scale)
                // Where a running pan is headed, else where it sits.
                val currentX = page.animationTargetX ?: page.x

                val c = if (isVertical && imageZoomType == ZoomStartPosition.RIGHT) -1 else 1
                val x = (currentX - c / page.scale).coerceIn(minX, maxX)

                if (!currentX.closeTo(x)) {
                    page.animateTo(targetX = x, targetY = page.y)
                    return
                }
            }

            navigateSpread(if (isReversed) -1 else 1)
        }
    }

    protected open fun moveLeft() {
        viewerState.getPage(0)?.let { page ->
            if (paged.navigateToPan) {
                val minX = page.minX(page.scale)
                val maxX = page.maxX(page.scale)
                val currentX = page.animationTargetX ?: page.x

                val c = if (isVertical && imageZoomType == ZoomStartPosition.RIGHT) -1 else 1
                val x = (currentX + c / page.scale).coerceIn(minX, maxX)

                if (!currentX.closeTo(x)) {
                    page.animateTo(targetX = x, targetY = page.y)
                    return
                }
            }

            navigateSpread(if (isReversed) 1 else -1)
        }
    }

    /** Target anchor page one spread past [from], in [direction] (positive = forward). */
    private fun nextPage(from: ViewerPage, direction: Int): ViewerPage? {
        var page = getSpreadAnchor(from)

        page = if (direction > 0) {
            if (page is ViewerReaderPage && spreadPartner(page) != null) {
                page.next?.next ?: return null
            } else {
                page.next ?: return null
            }
        } else {
            page.prev ?: return null
        }

        return getSpreadAnchor(page)
    }

    private fun navigateSpread(direction: Int) {
        val target = currentPage?.let { nextPage(it, direction) } ?: return
        moveToPage(target)
    }
}
