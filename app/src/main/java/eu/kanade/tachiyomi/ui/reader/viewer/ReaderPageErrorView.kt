package eu.kanade.tachiyomi.ui.reader.viewer

import android.content.Context
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.AbstractComposeView
import eu.kanade.presentation.reader.ReaderError
import eu.kanade.presentation.util.formattedMessage

/**
 * [ReaderError] for the view-based viewers, filling the view, in the theme of its reader-themed [context].
 */
class ReaderPageErrorView(context: Context) : AbstractComposeView(context) {

    private var error by mutableStateOf<Throwable?>(null)

    fun bind(error: Throwable?) {
        this.error = error
    }

    @Composable
    override fun Content() {
        ReaderThemedContent(context) {
            ReaderError(
                message = with(context) { error?.formattedMessage },
                onRetry = null,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}
