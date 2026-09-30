package com.diego.kiki.ui.browser

import android.view.ViewGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.diego.kiki.browser.TabState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WebViewContainer(
    activeTab: TabState?,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier
) {
    val webView = activeTab?.webView
    val isRefreshing = activeTab?.isLoading == true && activeTab.progress < 20

    Box(modifier = modifier.fillMaxSize()) {
        if (webView != null) {
            PullToRefreshBox(
                isRefreshing = isRefreshing,
                onRefresh = onRefresh,
                modifier = Modifier.fillMaxSize()
            ) {
                AndroidView(
                    factory = { _ ->
                        (webView.parent as? ViewGroup)?.removeView(webView)
                        webView.layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                        webView
                    },
                    update = { view ->
                        if (view != webView) {
                            (view.parent as? ViewGroup)?.removeView(view)
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }
}
