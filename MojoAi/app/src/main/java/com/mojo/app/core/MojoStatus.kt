package com.mojo.app.core

import com.mojo.app.ui.mascot.MojoState
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Live status shared between MojoEngine (the background service) and the UI.
 * Both run in the same process, so plain StateFlows are enough.
 */
object MojoStatus {
    /** What the mascot should be doing right now. */
    val state = MutableStateFlow(MojoState.SLEEP)

    /** True while MojoEngine is running in the foreground. */
    val running = MutableStateFlow(false)

    /** Last command MOJO heard, and what it answered. */
    val heard = MutableStateFlow("")
    val reply = MutableStateFlow("")
}
