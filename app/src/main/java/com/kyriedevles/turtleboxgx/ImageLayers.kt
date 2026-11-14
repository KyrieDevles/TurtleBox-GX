package com.kyriedevles.turtleboxgx

import android.graphics.BitmapFactory

/**
 * One extra picture on the card (an overframe, a glow, a sticker). Geometry is in layout pixels:
 * centre ([cx], [cy]), size ([w], [h]) before rotation, [rot] in degrees, optional mirror / flip.
 * [z] is compared with the `index` of the layout's own elements, so a layer can sit between them.
 */
class Layer(
    var id: String,
    var name: String,
    var z: Float,
    var vis: Boolean = true,
    var opacity: Float = 1f,
    var cx: Float = 0f,
    var cy: Float = 0f,
    var w: Float = 100f,
    var h: Float = 100f,
    var rot: Float = 0f,
    var mx: Boolean = false,
    var fy: Boolean = false,
) {
    fun copy() = Layer(id, name, z, vis, opacity, cx, cy, w, h, rot, mx, fy)
    val file: String get() = ImageLayers.file(id)
}

/** Layers live in the card's values (key `imglayers`) and their pictures in the card's files (`layers/<id>.png`), so a saved set carries both. */
object ImageLayers {
    const val KEY = "imglayers"

    fun file(id: String) = "layers/$id.png"

    fun newId(): String = java.lang.Long.toString(System.currentTimeMillis(), 36) + java.lang.Integer.toString((Math.random() * 1296).toInt(), 36)

    private fun f(v: Float) = String.format(java.util.Locale.US, "%.3f", v).trimEnd('0').trimEnd('.')

    fun read(values: Map<String, String>): MutableList<Layer> {
        val out = ArrayList<Layer>()
        for (line in (values[KEY] ?: "").split('\n')) {
            val p = line.split('\t')
            if (p.size < 12) continue
            try {
                out.add(Layer(p[0], p[1], p[2].toFloat(), p[3] == "1", p[4].toFloat(), p[5].toFloat(), p[6].toFloat(), p[7].toFloat(), p[8].toFloat(), p[9].toFloat(), p[10] == "1", p[11] == "1"))
            } catch (e: NumberFormatException) {
            }
        }
        return out
    }

    fun write(values: MutableMap<String, String>, list: List<Layer>) {
        if (list.isEmpty()) {
            values.remove(KEY)
            return
        }
        values[KEY] = list.joinToString("\n") { l ->
            listOf(l.id, l.name.replace('\t', ' ').replace('\n', ' '), f(l.z), if (l.vis) "1" else "0", f(l.opacity), f(l.cx), f(l.cy), f(l.w), f(l.h), f(l.rot), if (l.mx) "1" else "0", if (l.fy) "1" else "0").joinToString("\t")
        }
    }

    /** Fits a picture inside the whole card, centred, keeping its shape. A card-shaped overframe covers the card exactly. */
    fun fit(l: Layer, bw: Int, bh: Int, cardW: Float, cardH: Float) {
        val k = minOf(cardW / bw, cardH / bh)
        l.w = bw * k
        l.h = bh * k
        l.cx = cardW / 2f
        l.cy = cardH / 2f
        l.rot = 0f
    }

    fun size(bytes: ByteArray): Pair<Int, Int> {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
        return o.outWidth.coerceAtLeast(1) to o.outHeight.coerceAtLeast(1)
    }
}
