package sh.roboco.android.design

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp

/**
 * A horizontal scroller whose edges fade while there is more content that
 * way (android-ui-parity #10), the port of iOS `FadingScrollView`
 * (`ios:Design/EdgeFade.swift:59-86`): no fade at rest on the leading edge,
 * none once scrolled to the end, and the width ramps with travel so it
 * never pops.
 *
 * Consumers that need content padding inside the scroll (iOS
 * `contentInset`) put `Modifier.padding` on an inner `Row` *after* the
 * scroll; `maxValue` then includes it, as iOS `maxX` includes
 * `contentInset`. Scrollers that paint their own canvas use
 * [horizontalScrollEdgeFade] or [EdgeFade.scrollFades] +
 * [EdgeFade.horizontalMask] directly (ticket 116).
 */
@Composable
fun FadingHorizontalScroll(
    modifier: Modifier = Modifier,
    state: ScrollState = rememberScrollState(),
    fadeWidth: Dp = EdgeFade.Width,
    horizontalArrangement: Arrangement.Horizontal = Arrangement.Start,
    verticalAlignment: Alignment.Vertical = Alignment.Top,
    content: @Composable RowScope.() -> Unit,
) = Row(
    modifier.fadingHorizontalScroll(state, fadeWidth),
    horizontalArrangement = horizontalArrangement,
    verticalAlignment = verticalAlignment,
    content = content,
)
