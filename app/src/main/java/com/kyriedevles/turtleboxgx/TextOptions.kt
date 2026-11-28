package com.kyriedevles.turtleboxgx

/** Exact (unrounded) advance width of a string; Paint.measureText rounds up on some devices. */
fun exactWidth(p: android.graphics.Paint, s: String): Float =
    if (s.isEmpty()) 0f else p.getRunAdvance(s, 0, s.length, 0, s.length, false, s.length)

/** The text options of one box, already turned into numbers. Zero means "not set". */
data class TextOptions(
    val condense: Float = 0f, // 0.3..1.0 (or a percent such as 85)
    val fitMode: Int = 1, // 1 squeeze, 2 shrink first, 3 balanced, 4/5/6 squeeze presets, 7 keep size
    val minSizePct: Float = 0f,
    val maxSizePct: Float = 0f,
    val minLines: Float = 0f,
    val justify: Float = 0f, // 0 off, 100 light, 200 normal, 400 full, 500 balanced reflow
    val letterSpace: Float = 0f, // em
    val lastStretch: Float = 0f, // % of the last line's gap to the box width that is closed (0..100, fractions allowed)
    val hScale: Float = 100f, // horizontal scale % of the whole block (100 = normal)
    val vScale: Float = 100f, // vertical scale % of the whole block
    val baselineShift: Float = 0f, // px, positive raises the text
    val wordSpace: Float = 100f, // % of the normal space width
    val leadingPx: Float = 0f, // fixed line-to-line distance in px (0 = automatic, from the font)
    val fontScale: Float = 0f, // manual font size in % of the layout size (0 = automatic fit)
    val flow: Float = 0f, // % of the whole-text reflow toward the narrowest wrap with the same line count (0..100)
    val spacing: Float = 1f, // line spacing multiplier
    // Paragraph settings in card px (New engine only). First-line indent may be negative (hanging).
    val indentLeft: Float = 0f,
    val indentRight: Float = 0f,
    val indentFirst: Float = 0f,
    val spaceBefore: Float = 0f,
    val spaceAfter: Float = 0f,
    // Photoshop's Justification word spacing (New engine only): Minimum and Maximum in % of a space (0 = not set; Desired
    // is wordSpace), and the composer: 0 Single-line, 1 Every-line.
    val wordSpaceMin: Float = 0f,
    val wordSpaceMax: Float = 0f,
    val composer: Int = 0,
    // First baseline at the "d" height like Photoshop (New engine only; layout option firstbaseline = photoshop).
    val photoshopFirstBaseline: Boolean = false,
    // Justification (New engine): -1 old rule, 0 none, 1 justify full lines, 2 justify all. See LayoutOptions.justifyMode.
    val justifyMode: Int = -1,
    // Lines that do not fit in the box are not drawn, like Photoshop's overset text (New engine; <overflow>hide</overflow>).
    val hideOverflow: Boolean = false,
    // Automatic leading is 120% of the size, like Photoshop (New engine; <autoleading>photoshop</autoleading>).
    // Leading % scales it; Leading px and fit steps replace it.
    val autoLeading: Boolean = false,
    // Tracking goes after each letter, like Photoshop, instead of half before and half after (New engine;
    // <trackingside>after</trackingside>). Letters move by half the tracking; widths and line breaks stay the same.
    val trackAfter: Boolean = false,
)
