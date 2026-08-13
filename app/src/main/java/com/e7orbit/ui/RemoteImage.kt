package com.e7orbit.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import com.e7orbit.data.IconAssetStore

@Composable
internal fun RemoteImage(
    url: String?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
) {
    val context = LocalContext.current
    var state by remember(url) { mutableStateOf<RemoteImageState>(RemoteImageState.Loading) }

    LaunchedEffect(url) {
        state = when {
            url.isNullOrBlank() -> RemoteImageState.Unavailable
            else -> IconAssetStore.load(context, url)
                ?.let(RemoteImageState::Loaded)
                ?: RemoteImageState.Unavailable
        }
    }

    when (val imageState = state) {
        // Transparent while loading / on failure instead of a solid box, so icons never
        // flash as a white placeholder during a slow first load.
        RemoteImageState.Loading,
        RemoteImageState.Unavailable,
        -> Box(modifier = modifier)

        is RemoteImageState.Loaded -> Image(
            bitmap = imageState.bitmap.asImageBitmap(),
            contentDescription = contentDescription,
            modifier = modifier,
            contentScale = contentScale,
        )
    }
}

private sealed interface RemoteImageState {
    data object Loading : RemoteImageState
    data object Unavailable : RemoteImageState
    data class Loaded(val bitmap: Bitmap) : RemoteImageState
}
