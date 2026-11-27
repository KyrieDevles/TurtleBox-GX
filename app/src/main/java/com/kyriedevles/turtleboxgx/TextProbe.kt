package com.kyriedevles.turtleboxgx

import android.graphics.Bitmap
import android.graphics.RectF
import android.os.Handler
import android.os.Looper

/**
 * The edit menu's text preview: while a menu with a text field is open, each render also crops the text box it draws
 * (the one showing `{{[name]}}`) and reports where every letter is, so the menu can show the text and let it be
 * highlighted. Box text layers are drawn alone, unrotated.
 */
object TextProbe {
    /** The text field the open menu edits (a data name), or null when no preview is wanted. */
    @Volatile
    var name: String? = null

    class Preview(
        val bitmap: Bitmap,
        /** Per character of [engineText]: its box in [bitmap] pixels, or null. */
        val rects: Array<RectF?>,
        val engineText: String,
    )

    var listener: ((Preview) -> Unit)? = null

    private val main = Handler(Looper.getMainLooper())

    fun publish(p: Preview) {
        main.post { listener?.invoke(p) }
    }
}
