package eu.kanade.tachiyomi.util.system

import android.content.Context
import android.webkit.WebView

object WebViewUtil {

    fun getVersion(context: Context): String {
        val webView = WebView.getCurrentWebViewPackage() ?: return "how did you get here?"
        val pm = context.packageManager
        val label = webView.applicationInfo!!.loadLabel(pm)
        val version = webView.versionName
        return "$label $version"
    }
}
