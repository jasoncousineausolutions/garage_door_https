package com.example.garagedooropener

// =============================================================================
// User preferences
//
// Deliberately NOT part of the pasted JSON config. That file holds credentials
// for a device and is meant to be handed between phones; these are personal
// choices about how the app behaves, and shouldn't ride along when a config is
// shared with someone else.
// =============================================================================

import android.content.Context

class Prefs(context: Context) {

    private val sp = context.getSharedPreferences("garage-prefs", Context.MODE_PRIVATE)

    /**
     * Require a deliberate slide rather than a tap.
     *
     * On by default: a stray tap on a phone in a pocket opening a garage door
     * is a real failure mode, and the cost of the safer default is one extra
     * gesture.
     */
    var useSlider: Boolean
        get() = sp.getBoolean(KEY_SLIDER, true)
        set(value) = sp.edit().putBoolean(KEY_SLIDER, value).apply()

    /**
     * Show the activity log on the main screen.
     *
     * Off by default: it's a diagnostic tool, and with it visible the screen
     * reads as a debugging console rather than a door opener. Still one tap
     * away when something needs investigating.
     */
    var showLogs: Boolean
        get() = sp.getBoolean(KEY_LOGS, false)
        set(value) = sp.edit().putBoolean(KEY_LOGS, value).apply()

    private companion object {
        const val KEY_SLIDER = "use_slider"
        const val KEY_LOGS = "show_logs"
    }
}
