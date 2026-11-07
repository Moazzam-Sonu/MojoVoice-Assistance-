package com.mojo.app.ui.mascot

/**
 * MOJO mascot states. Same set as the React MojoMascot, plus SPEAKING for
 * when MOJO answers out loud.
 */
enum class MojoState {
    IDLE,
    LOOKING,
    GREETING,
    TYPING,
    READING,
    THINKING,
    LISTENING,
    SPEAKING,
    SUCCESS,
    SLEEP,
    FOCUS,
    EXCITED;

    /** States that drift into reading, then sleep, when nobody interacts. */
    val canDoze: Boolean get() = this == IDLE || this == LOOKING || this == GREETING || this == SUCCESS

    /** States where the eyes follow your finger (or wander on their own). */
    val followsPointer: Boolean
        get() = this == IDLE || this == LOOKING || this == GREETING || this == SUCCESS || this == EXCITED
}
