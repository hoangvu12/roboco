package sh.roboco.android.transcript

/**
 * A painted link under a tap, if any. Ticket 126 implements the hit test
 * (row-local dp coordinates against `RowDisplay.links`) and the routing;
 * until then no tap opens a link.
 */
internal fun linkAt(row: RealizedRow, xDp: Float, yDp: Float): String? = null // ticket 126
