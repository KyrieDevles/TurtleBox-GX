package com.kyriedevles.turtleboxgx

import android.os.Handler
import android.os.Looper

/**
 * What fit steps chose on the card last drawn, so the edit menu can show it. A text box with fit steps links up to four
 * menu fields through its option tags (`<sizepx>{{sizepx_effect}}</sizepx>` and so on): while the box sizes itself
 * ("auto"), those fields show the values the card really uses, dimmed. Editing one of them freezes all four into the
 * card's values ("manual"), exactly what Photoshop's panels would show; Back to auto fit clears them again.
 *
 * Nothing here is tied to the effect box: any `<text>` with fit steps and these tags takes part, and a text layer can
 * later publish its own group the same way.
 */
object AutoFit {
    class Group(val size: String?, val leading: String?, val tracking: String?, val hscale: String?) {
        /** Fit steps are switched on for the box. */
        var on = false
        /** The box is sizing itself (no size typed by hand). */
        var auto = false
        /** The step used (0-based; -1 = below the last step) and how many steps there are. */
        var step = -1
        var steps = 0
        /** Horizontal squeeze the fit applied, 1 = none. */
        var squeeze = 1f
        /** Data name to the value the card uses now. */
        val values = HashMap<String, Float>()

        fun names(): List<String> = listOfNotNull(size, leading, tracking, hscale)
    }

    @Volatile
    private var groups: List<Group> = emptyList()

    /** Called on the main thread after each published render (the open edit menu refreshes its fields). */
    var listener: (() -> Unit)? = null

    private val main = Handler(Looper.getMainLooper())

    fun publish(g: List<Group>) {
        groups = g
        main.post { listener?.invoke() }
    }

    /** The group a menu field belongs to: by one of its four field names, or by any name the group lists. */
    fun groupFor(name: String): Group? = groups.firstOrNull { name in it.names() }
}
