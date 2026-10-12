package sh.roboco.android.design

/**
 * Re-emits SVG path data as single-space-separated tokens so androidx
 * `PathParser` can read arc flags that the source packs without separators
 * ("0 01.148" → "0 0 1 .148"). A port of the iOS tokenizer
 * (`ios:Design/BrandMarks.swift:294-372`), including the rule that arc
 * arguments 4 and 5 of each 7-argument group are single-character flags.
 */
internal object SvgPathTokens {

    fun normalize(d: String): String {
        val out = StringBuilder(d.length + d.length / 4)
        fun sep() { if (out.isNotEmpty()) out.append(' ') }
        fun emit(s: CharSequence) { sep(); out.append(s) }
        fun emitRange(src: String, start: Int, end: Int) { sep(); out.append(src, start, end) }
        var i = 0
        var cmd = ' '
        var arg = 0                          // args consumed since the last command letter
        while (true) {
            while (i < d.length && (d[i] == ',' || d[i].isWhitespace())) i++
            if (i >= d.length) break
            val c = d[i]
            if (c.isLetter()) {              // 'e'/'E' never start a token; they are eaten inside numbers
                emit(c.toString()); cmd = c; arg = 0; i++; continue
            }
            if ((cmd == 'a' || cmd == 'A') && (arg % 7 == 3 || arg % 7 == 4)) {
                require(c == '0' || c == '1') { "arc flag expected at $i in path data" }
                emit(c.toString()); i++; arg++; continue
            }
            val start = i
            if (d[i] == '+' || d[i] == '-') i++
            var dot = false
            while (i < d.length) {
                val ch = d[i]
                when {
                    ch.isDigit() -> i++
                    ch == '.' && !dot -> { dot = true; i++ }
                    ch == 'e' || ch == 'E' -> { i++; if (i < d.length && (d[i] == '+' || d[i] == '-')) i++ }
                    else -> break                // second '.', '-', letter, separator end the number
                }
            }
            require(i > start) { "unexpected '${d[i]}' at $i in path data" }
            emitRange(d, start, i); arg++
        }
        return out.toString()
    }
}
