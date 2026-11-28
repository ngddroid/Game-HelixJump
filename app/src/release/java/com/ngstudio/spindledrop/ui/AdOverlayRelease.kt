package com.ngstudio.spindledrop.ui

import androidx.compose.runtime.Composable
import com.ngstudio.spindledrop.AppServices

/** Release builds: no ads, so there is no ad overlay. */
@Composable
@Suppress("UNUSED_PARAMETER")
fun AdOverlay(services: AppServices) {
    // No ads in release.
}
