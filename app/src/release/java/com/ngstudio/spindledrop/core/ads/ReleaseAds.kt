package com.ngstudio.spindledrop.core.ads

/** Variant factory (release): no ads, so every "Watch Ad" button and ad-unlock is hidden. */
fun createAdGateway(): AdGateway = NoAdGateway()
