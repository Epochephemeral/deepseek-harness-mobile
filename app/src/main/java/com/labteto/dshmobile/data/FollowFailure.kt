package com.labteto.dshmobile.data

import com.labteto.dshmobile.core.wire.RemoteStreamException
import kotlinx.coroutines.CancellationException

/** ConnectionLoop owns carrier recovery; only the current follow can report an endpoint failure. */
internal fun shouldReportFollowFailure(failure: Throwable, currentGeneration: Boolean): Boolean =
    currentGeneration && failure !is CancellationException &&
        !(failure is RemoteStreamException && failure.carrier)
