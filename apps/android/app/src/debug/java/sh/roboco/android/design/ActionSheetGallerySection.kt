package sh.roboco.android.design

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import sh.roboco.android.core.Face

/**
 * The "Action sheet" page of the debug design gallery (android-ui-parity
 * #19): one row per sheet shape from the ticket's known callers, every one
 * shown through a real [ActionSheetHost] so the scrim, back, hardware
 * Escape, the one-shot guard and the handler-after-dismiss order are exactly
 * what the app gets. The section owns its host — the gallery page is not a
 * window root — and the result line shows what the last sheet's callback
 * produced ("selected: Dither" / "cancelled").
 *
 * Registered in `Gallery.sections` as "Action sheet" (slug
 * `action_sheet`), `GalleryLayout.Fill`: the host overlays the whole
 * section, and the "Many rows" sheet scrolls inside the action card.
 * Registered by this ticket as
 * `GallerySection("Action sheet", GalleryLayout.Fill) { ActionSheetGallerySection() }`.
 *
 * The wallpaper sheet's checked row is a gallery-local
 * `mutableStateOf`: picking an effect moves the checkmark, so reopening the
 * sheet shows the new current effect, like `WallpaperStore.effect`.
 *
 * Manual check per the ticket: gesture navigation keeps the Cancel card
 * above the handle (`max(navBottom, 8)`); 3-button navigation keeps it
 * above the button bar; TalkBack swipes title → five rows ("Dither,
 * Selected") → Cancel and nothing behind the sheet; on API 35 the cards
 * blur the backdrop, on API 31–32 they show the fallback fill.
 */
@Composable
fun ActionSheetGallerySection() {
    val sheets = remember { ActionSheetHostState() }
    val palette = RobocoTheme.palette
    val typeScale = LocalTypeScale.current
    val hintStyle = uiStyle(Face.Sans, typeScale.scaled(13f), palette.secondary)
    var result by remember { mutableStateOf("—") }
    // The current Wallpaper Effect (Dither), so the sheet's checked row follows the pick.
    var effect by remember { mutableStateOf(1) }

    ActionSheetHost(state = sheets) {
        Box(Modifier.fillMaxSize()) {
            GalleryBackdrop(Modifier.matchParentSize())
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .windowInsetsPadding(
                        WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom),
                    )
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                BasicText(
                    "Action sheet",
                    style = uiStyle(Face.SansSemibold, typeScale.scaled(17f), palette.text),
                )
                BasicText(
                    "Every sheet below runs through a real ActionSheetHost: scrim, back, " +
                        "Escape and the handler-after-dismiss order included.",
                    style = hintStyle,
                )

                // Ticket 43/44: title only, one default row per section, "No Section" last.
                SheetDemoRow("Move to Section") {
                    sheets.show(
                        ActionSheetModel(
                            title = "Move to Section",
                            actions = listOf(
                                "Backend",
                                "Design review",
                                "A very long section name that will not fit on one line",
                                "No Section",
                            ).map { name ->
                                ActionSheetAction(name) { result = "selected: $name" }
                            },
                            onCancel = { result = "cancelled" },
                        ),
                    )
                }

                // Ticket 43/45: title + message + one destructive row (curly quotes, iOS copy).
                SheetDemoRow("Delete section") {
                    sheets.show(
                        ActionSheetModel(
                            title = "Delete “Backend”?",
                            message = "Its sessions move back to the main list.",
                            actions = listOf(
                                ActionSheetAction(
                                    "Delete Section",
                                    style = ActionSheetStyle.Destructive,
                                ) { result = "selected: Delete Section" },
                            ),
                            onCancel = { result = "cancelled" },
                        ),
                    )
                }

                // Ticket 97: the current effect checked, the pick moves the checkmark.
                SheetDemoRow("Wallpaper Effect") {
                    sheets.show(
                        wallpaperEffectSheet(
                            checked = effect,
                            onSelect = { index ->
                                effect = index
                                result = "selected: ${WallpaperEffectLabels[index]}"
                            },
                            onCancel = { result = "cancelled" },
                        ),
                    )
                }

                // Ticket 43: the "Create on" engine chooser (title + message + N rows).
                SheetDemoRow("Create on") {
                    sheets.show(
                        ActionSheetModel(
                            title = "New Section",
                            message = "Create on",
                            actions = listOf("Studio Mac", "Linux box", "Windows desktop").map { engine ->
                                ActionSheetAction(engine) { result = "selected: $engine" }
                            },
                            onCancel = { result = "cancelled" },
                        ),
                    )
                }

                // A long list: the action card hits the height cap, the rows scroll.
                SheetDemoRow("Many rows") {
                    sheets.show(
                        ActionSheetModel(
                            actions = (1..20).map { i ->
                                ActionSheetAction("Section $i") { result = "selected: Section $i" }
                            },
                            onCancel = { result = "cancelled" },
                        ),
                    )
                }

                BasicText("Last result: $result", style = hintStyle)
            }
        }
    }
}

/** One demo row: a full-width tappable label (the gallery's flat-button look). */
@Composable
private fun SheetDemoRow(label: String, onClick: () -> Unit) {
    val palette = RobocoTheme.palette
    val typeScale = LocalTypeScale.current
    BasicText(
        label,
        style = uiStyle(Face.Sans, typeScale.scaled(15f), palette.text),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
    )
}
