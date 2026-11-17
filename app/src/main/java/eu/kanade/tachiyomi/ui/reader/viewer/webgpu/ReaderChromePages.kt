package eu.kanade.tachiyomi.ui.reader.viewer.webgpu

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import ca.mpreg.webgpuviewer.viewer.ComposePageSize
import ca.mpreg.webgpuviewer.viewer.ImagePage
import eu.kanade.presentation.reader.ChapterTransition
import eu.kanade.presentation.reader.ReaderError
import eu.kanade.presentation.util.formattedMessage
import eu.kanade.tachiyomi.ui.reader.model.ChapterTransition
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import eu.kanade.tachiyomi.ui.reader.viewer.ReaderThemedContent

/** The side padding `PagerTransitionHolder` gave the same transition view. */
private const val TRANSITION_SIDE_PADDING = 64

/** The padding `WebtoonTransitionHolder` gave it, which took only the height it needed. */
private const val STRIP_TRANSITION_SIDE_PADDING = 32
private const val STRIP_TRANSITION_VERTICAL_PADDING = 128

/** How much of the viewport `WebtoonPageHolder` gave its error layout. */
private const val STRIP_ERROR_HEIGHT_FRACTION = 0.8f

/** The top margin `WebtoonPageHolder` gave its spinner, as a fraction of the viewport. */
private const val STRIP_PROGRESS_TOP_FRACTION = 0.25f

/**
 * The reader's own chapter and page chrome, as composables, in the reader's theme.
 *
 * Built on the reader background, which is fixed for the page's life: a theme change rebuilds every
 * page (see [WebGpuViewer]'s image property listener).
 */
abstract class ReaderComposePage(
    protected val viewer: WebGpuViewer,
    size: ComposePageSize = ComposePageSize.Auto,
) : ImagePage.Compose(size, viewer.readerBackgroundColor()) {

    /**
     * The theme goes on here because the library's page knows nothing of the app's colours.
     */
    @Composable
    final override fun BoxScope.Content() {
        ReaderThemedContent(viewer.readerThemedContext) { PageContent() }
    }

    @Composable
    protected abstract fun PageContent()
}

/**
 * A page that failed to decode, with the pager's error layout: the message and Retry.
 */
class ErrorPage internal constructor(
    viewer: WebGpuViewer,
    private val viewerPage: WebGpuViewer.ViewerReaderPage?,
    private val error: Throwable?,
) : ReaderComposePage(
    viewer,
    // WebtoonPageHolder gave its error layout less than a screen, so the page below shows through.
    if (viewer is WebGpuViewerContinuous) ComposePageSize.Wrap else ComposePageSize.Auto,
) {

    @Composable
    override fun PageContent() {
        val context = LocalContext.current
        val strip = if (viewer is WebGpuViewerContinuous) {
            Modifier.fillMaxHeight(STRIP_ERROR_HEIGHT_FRACTION).wrapContentHeight()
        } else {
            Modifier
        }

        ReaderError(
            modifier = strip,
            message = with(context) { error?.formattedMessage },
            onRetry = { viewerPage?.let { viewer.retryLoad(it) } },
        )
    }
}

/** A page still decoding: the reader's progress ring. */
class ProgressPage(viewer: WebGpuViewer) : ReaderComposePage(viewer) {
    init {
        isPlaceholder = true
    }

    @Composable
    override fun PageContent() {
        if (viewer !is WebGpuViewerContinuous) {
            CircularProgressIndicator()
            return
        }
        // WebtoonPageHolder centred its spinner below a quarter-height top margin, which puts it
        // at five eighths of the slot rather than halfway down.
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = maxHeight * STRIP_PROGRESS_TOP_FRACTION),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
        }
    }
}

/** The page between two chapters, or before the first and after the last. */
class TransitionPage(
    viewer: WebGpuViewer,
    val prevChapter: ReaderChapter?,
    val nextChapter: ReaderChapter?,
) : ReaderComposePage(viewer, ComposePageSize.Wrap) {

    /** As the pager and webtoon adapters framed it: leaving [prevChapter] for [nextChapter]. */
    private val transition: ChapterTransition? = when {
        prevChapter != null -> ChapterTransition.Next(prevChapter, nextChapter)
        nextChapter != null -> ChapterTransition.Prev(nextChapter, null)
        else -> null
    }

    @Composable
    override fun PageContent() {
        val transition = transition ?: return
        CompositionLocalProvider(
            LocalTextStyle provides MaterialTheme.typography.bodySmall,
            LocalContentColor provides MaterialTheme.colorScheme.onBackground,
        ) {
            // The padding the holder each viewer used to inflate this into gave it.
            val padding = if (viewer is WebGpuViewerContinuous) {
                Modifier.padding(
                    horizontal = STRIP_TRANSITION_SIDE_PADDING.dp,
                    vertical = STRIP_TRANSITION_VERTICAL_PADDING.dp,
                )
            } else {
                Modifier.padding(horizontal = TRANSITION_SIDE_PADDING.dp)
            }
            Box(modifier = padding.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                ChapterTransition(transition = transition)
            }
        }
    }
}
