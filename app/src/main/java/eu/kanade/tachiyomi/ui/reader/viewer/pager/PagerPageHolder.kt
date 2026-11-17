package eu.kanade.tachiyomi.ui.reader.viewer.pager

import android.annotation.SuppressLint
import android.content.Context
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.viewer.ReaderPageErrorView
import eu.kanade.tachiyomi.ui.reader.viewer.ReaderPageImageView
import eu.kanade.tachiyomi.ui.reader.viewer.processPagedImage
import eu.kanade.tachiyomi.widget.ViewPagerAdapter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import logcat.LogPriority
import okio.Buffer
import tachiyomi.core.common.util.system.ImageUtil
import tachiyomi.core.common.util.system.logcat

/**
 * View of the ViewPager that contains a page of a chapter.
 */
@SuppressLint("ViewConstructor")
class PagerPageHolder(
    readerThemedContext: Context,
    val viewer: PagerViewer,
    val page: ReaderPage,
) : ReaderPageImageView(readerThemedContext), ViewPagerAdapter.PositionableView {

    /**
     * Item that identifies this view. Needed by the adapter to not recreate views.
     */
    override val item
        get() = page

    /**
     * Error layout to show when the image fails to load.
     */
    private var errorLayout: ReaderPageErrorView? = null

    private val scope = MainScope()

    /**
     * Job for loading the page.
     */
    private var loadJob: Job? = null

    init {
        pageColorFilter = viewer.pageColorFilter
        loadJob = scope.launch { setImage() }
    }

    /**
     * Called when this view is detached from the window. Unsubscribes any active subscription.
     */
    @SuppressLint("ClickableViewAccessibility")
    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        loadJob?.cancel()
        loadJob = null
    }

    private suspend fun setImage() {
        // An unloaded chapter has closed the files its pages read from
        if (page.chapter.pageLoader == null) return
        val streamFn = page.stream ?: return

        try {
            val buffer = withContext(Dispatchers.IO) { streamFn().use { Buffer().readFrom(it) } }
            // Splitting, merging and sampling the background decode the image, which is CPU work
            val (source, isAnimated, background) = withContext(Dispatchers.Default) {
                val source = viewer.simplePaged.processPagedImage(item, buffer, viewer is L2RPagerViewer) {
                    viewer.onPageSplit(item, it)
                }
                val isAnimated = ImageUtil.isAnimatedAndSupported(source)
                val background = if (!isAnimated && viewer.general.automaticBackground) {
                    ImageUtil.chooseBackground(context, source.peek().inputStream())
                } else {
                    null
                }
                Triple(source, isAnimated, background)
            }
            withContext(Dispatchers.Main) {
                setImage(
                    source,
                    isAnimated,
                    Config(
                        zoomDuration = viewer.general.doubleTapAnimDuration,
                        minimumScaleType = viewer.paged.page.imageScaleType.value,
                        cropBorders = viewer.paged.page.cropBorders,
                        zoomStartPosition = viewer.imageZoomType,
                        landscapeZoom = viewer.paged.page.landscapeZoom,
                    ),
                )
                if (!isAnimated) {
                    pageBackground = background
                }
                removeErrorLayout()
            }
        } catch (e: Throwable) {
            logcat(LogPriority.ERROR, e)
            withContext(Dispatchers.Main) {
                showErrorLayout(e)
            }
        }
    }

    /**
     * Called when an image fails to decode.
     */
    override fun onImageLoadError(error: Throwable?) {
        super.onImageLoadError(error)
        showErrorLayout(error)
    }

    /**
     * Called when an image is zoomed in/out.
     */
    override fun onScaleChanged(newScale: Float) {
        super.onScaleChanged(newScale)
        viewer.activity.hideMenu()
    }

    private fun showErrorLayout(error: Throwable?) {
        val errorLayout = errorLayout ?: ReaderPageErrorView(context).also {
            addView(it, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
            errorLayout = it
        }
        errorLayout.bind(error)
    }

    /**
     * Removes the decode error layout from the holder, if found.
     */
    private fun removeErrorLayout() {
        errorLayout?.let(::removeView)
        errorLayout = null
    }
}
