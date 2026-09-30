package com.diego.kiki.ui.browser

import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.viewinterop.AndroidView
import com.diego.kiki.ui.FullscreenVideoState

@Composable
fun FullscreenVideoLayout(
    fullscreenState: FullscreenVideoState,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        AndroidView(
            factory = { _ ->
                val view = fullscreenState.view
                (view.parent as? ViewGroup)?.removeView(view)
                view.layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                view
            },
            modifier = Modifier.fillMaxSize()
        )
    }
}
