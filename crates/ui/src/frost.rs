//! [`frosted`] — the frosted-glass float: wraps a popover/dialog card so its
//! ENTIRE subtree paints inside one scene layer (a single draw order) with a
//! backdrop blur painted first.
//!
//! The single layer order is the point: with per-primitive bounds-tree
//! ordering, a hover repaint elsewhere could reassign the card's quads BELOW
//! the blur — washes, dividers, and borders intermittently got snapshotted and
//! blurred away (user reports). Inside one layer the blur/content relationship
//! is structural: blur first, then shadow, tint, border, rows, text.

use gpui::{
    AnyElement, App, Background, Bounds, BoxShadow, Corners, Element, GlobalElementId, Hsla,
    InspectorElementId, IntoElement, LayoutId, Pixels, Styled, Window, hsla, linear_color_stop,
    linear_gradient, point, px,
};

use crate::theme::Theme;

/// Shared backdrop-blur sigma for floating menus, popovers and palettes —
/// one surface treatment for every popup layer (upstream #403). The VALUE
/// stays at roboco's 44, not upstream's 16: the reference roboco
/// `.glass-surface` runs `blur(44px)` (feature-inventory §1.12), and the
/// [`Theme::glass_overlay`] tint is thin enough that a 16px blur left
/// backdrop detail ghosting through menu rows. The composer pill keeps its
/// own lighter 16 (`chat-composer-glass` blurs 12–16 in the reference).
pub const MENU_BLUR: f32 = 44.0;

/// Frost `child` (a popover card): backdrop-blurred on glass, pass-through on
/// opaque platforms. `corner_radius` must match the card's rounding.
pub fn frosted(corner_radius: f32, blur_radius: f32, child: impl IntoElement) -> Frosted {
    Frosted {
        corner_radius,
        blur_radius,
        child: child.into_any_element(),
    }
}

pub struct Frosted {
    corner_radius: f32,
    blur_radius: f32,
    child: AnyElement,
}

impl Element for Frosted {
    type RequestLayoutState = ();
    type PrepaintState = ();

    fn id(&self) -> Option<gpui::ElementId> {
        None
    }

    fn source_location(&self) -> Option<&'static core::panic::Location<'static>> {
        None
    }

    fn request_layout(
        &mut self,
        _id: Option<&GlobalElementId>,
        _inspector_id: Option<&InspectorElementId>,
        window: &mut Window,
        cx: &mut App,
    ) -> (LayoutId, ()) {
        (self.child.request_layout(window, cx), ())
    }

    fn prepaint(
        &mut self,
        _id: Option<&GlobalElementId>,
        _inspector_id: Option<&InspectorElementId>,
        _bounds: Bounds<Pixels>,
        _request_layout: &mut Self::RequestLayoutState,
        window: &mut Window,
        cx: &mut App,
    ) {
        self.child.prepaint(window, cx);
    }

    fn paint(
        &mut self,
        _id: Option<&GlobalElementId>,
        _inspector_id: Option<&InspectorElementId>,
        bounds: Bounds<Pixels>,
        _request_layout: &mut Self::RequestLayoutState,
        _prepaint: &mut Self::PrepaintState,
        window: &mut Window,
        cx: &mut App,
    ) {
        if Theme::of(cx).is_frost() {
            window.paint_layer(bounds, |window| {
                window.paint_backdrop_blur(
                    bounds,
                    Corners::all(px(self.corner_radius)),
                    px(self.blur_radius),
                );
                self.child.paint(window, cx);
            });
        } else {
            self.child.paint(window, cx);
        }
    }
}

impl IntoElement for Frosted {
    type Element = Self;

    fn into_element(self) -> Self::Element {
        self
    }
}

/// Paint `child` in its own scene layer, giving it a fresh draw order above
/// everything painted so far in the enclosing layer.
///
/// Needed for overlays INSIDE a frosted card: the card's single layer means
/// every primitive shares one draw order, and equal orders render grouped by
/// primitive kind (quads, then icons, then images) — so a close button's
/// circle painted "after" a thumbnail still shows up UNDER the image. A
/// nested layer restores the intended stacking.
pub fn layered(child: impl IntoElement) -> Layered {
    Layered {
        child: child.into_any_element(),
    }
}

pub struct Layered {
    child: AnyElement,
}

impl Element for Layered {
    type RequestLayoutState = ();
    type PrepaintState = ();

    fn id(&self) -> Option<gpui::ElementId> {
        None
    }

    fn source_location(&self) -> Option<&'static core::panic::Location<'static>> {
        None
    }

    fn request_layout(
        &mut self,
        _id: Option<&GlobalElementId>,
        _inspector_id: Option<&InspectorElementId>,
        window: &mut Window,
        cx: &mut App,
    ) -> (LayoutId, ()) {
        (self.child.request_layout(window, cx), ())
    }

    fn prepaint(
        &mut self,
        _id: Option<&GlobalElementId>,
        _inspector_id: Option<&InspectorElementId>,
        _bounds: Bounds<Pixels>,
        _request_layout: &mut Self::RequestLayoutState,
        window: &mut Window,
        cx: &mut App,
    ) {
        self.child.prepaint(window, cx);
    }

    fn paint(
        &mut self,
        _id: Option<&GlobalElementId>,
        _inspector_id: Option<&InspectorElementId>,
        bounds: Bounds<Pixels>,
        _request_layout: &mut Self::RequestLayoutState,
        _prepaint: &mut Self::PrepaintState,
        window: &mut Window,
        cx: &mut App,
    ) {
        window.paint_layer(bounds, |window| self.child.paint(window, cx));
    }
}

impl IntoElement for Layered {
    type Element = Self;

    fn into_element(self) -> Self::Element {
        self
    }
}

// ---------------------------------------------------------------------------

/// Glass surfaces shared by controls (upstream #471's `glass.rs`, folded
/// into this glass/frost module rather than a parallel one): each is a
/// vertical gradient (lit from above), a hairline rim, an inner top
/// highlight, and a soft drop. Dark appearances keep the structure with
/// translucent white instead of opaque fills. [`light`] is the neutral plate
/// (a track), [`accent_plate`] the active plate (Stop, an enabled switch, a
/// slider's fill) and [`thumb`] the opaque handle that rides on either.
fn glass_shadow(color: Hsla, y: f32, blur: f32, spread: f32, inset: bool) -> BoxShadow {
    BoxShadow {
        color,
        offset: point(px(0.0), px(y)),
        blur_radius: px(blur),
        spread_radius: px(spread),
        inset,
    }
}

fn glass_white(alpha: f32) -> Hsla {
    hsla(0.0, 0.0, 1.0, alpha)
}

fn glass_black(alpha: f32) -> Hsla {
    hsla(0.0, 0.0, 0.0, alpha)
}

fn vertical_glass(top: Hsla, bottom: Hsla) -> Background {
    linear_gradient(
        180.0,
        linear_color_stop(top, 0.0),
        linear_color_stop(bottom, 1.0),
    )
}

/// A lighter tint of `color` for the lit top of a gradient.
fn lift(color: Hsla, amount: f32) -> Hsla {
    crate::motion::mix(color, glass_white(color.a), amount)
}

/// One glass surface, applicable to an element or paintable in a canvas.
pub(crate) struct Plate {
    background: Background,
    rim: Hsla,
    shadows: Vec<BoxShadow>,
}

impl Plate {
    fn apply<E: Styled>(self, el: E) -> E {
        el.bg(self.background)
            .border_1()
            .border_color(self.rim)
            .shadow(self.shadows)
    }

    /// Paints the plate the way a styled element would: drops beneath the
    /// fill, the rim on it, and inset light above it.
    pub(crate) fn paint(&self, window: &mut Window, bounds: Bounds<Pixels>, radius: f32) {
        let corners = Corners::all(px(radius));
        let (inset, drop): (Vec<_>, Vec<_>) = self.shadows.iter().cloned().partition(|s| s.inset);
        window.paint_drop_shadows(bounds, corners, &drop);
        window.paint_quad(gpui::quad(
            bounds,
            corners,
            self.background,
            px(1.0),
            self.rim,
            gpui::BorderStyle::default(),
        ));
        window.paint_inset_shadows(bounds, corners, &inset);
    }
}

/// Neutral glass. `t` fades the whole treatment in. Dark appearances lift
/// translucent white off the surface; light ones sink a translucent tint
/// into it (shaded under the top edge, lit inside the bottom edge), since a
/// near-white plate on a near-white surface washes out.
pub(crate) fn light_plate(theme: &Theme, t: f32) -> Plate {
    if theme.appearance.is_dark() {
        return Plate {
            background: vertical_glass(glass_white(0.08 * t), glass_white(0.05 * t)),
            rim: glass_white(0.09 * t),
            shadows: vec![
                glass_shadow(glass_white(0.07 * t), 1.0, 0.0, 0.0, true),
                glass_shadow(glass_black(0.16 * t), 1.0, 2.0, 0.0, false),
            ],
        };
    }
    Plate {
        background: vertical_glass(glass_black(0.075 * t), glass_black(0.04 * t)),
        rim: glass_black(0.08 * t),
        // Inset only: GPUI paints drop shadows under the whole box, so an
        // outer lip would show through the translucent fill and whiten it.
        shadows: vec![
            glass_shadow(glass_black(0.08 * t), 1.0, 2.0, 0.0, true),
            glass_shadow(glass_white(0.55 * t), -1.0, 0.0, 0.0, true),
        ],
    }
}

/// Accent glass. `t` fades it in over the resting control; `glow` (0–1)
/// spreads its coloured shadow.
pub(crate) fn accent_plate(theme: &Theme, t: f32, glow: f32) -> Plate {
    let dark = theme.appearance.is_dark();
    // The theme's fill token, as every other accent fill uses. Dark plates
    // keep their lighting faint so the token reads as itself, not a pastel.
    let base = theme.accent_strong;
    let top = lift(base, if dark { 0.06 } else { 0.22 });
    // Light: a defined edge a shade deeper than the fill, with a paler ring
    // just inside it. Dark: a soft lifted rim.
    let rim = if dark {
        lift(base, 0.35).opacity(0.35)
    } else {
        crate::motion::mix(base, glass_black(1.0), 0.14)
    };
    let highlight = glass_white(if dark { 0.12 } else { 0.32 });
    let ring = glass_white(if dark { 0.0 } else { 0.22 });
    // Dark plates sit flat at rest; light ones keep a faint coloured glow.
    let halo = base.opacity((if dark { 0.0 } else { 0.14 } + 0.3 * glow) * t);
    Plate {
        background: vertical_glass(top.opacity(t), base.opacity(t)),
        rim: rim.opacity(t),
        shadows: vec![
            glass_shadow(highlight.opacity(t), 1.0, 0.0, 0.0, true),
            glass_shadow(ring.opacity(t), 0.0, 0.0, 1.0, true),
            glass_shadow(halo, 2.0 + 2.0 * glow, 6.0 + 14.0 * glow, 0.0, false),
        ],
    }
}

/// The opaque handle: the light-appearance neutral plate in every
/// appearance, so it stays solid over translucent dark glass, with the
/// appearance's own drop beneath it.
pub(crate) fn thumb_plate(theme: &Theme) -> Plate {
    let dark = theme.appearance.is_dark();
    let mut shadows = vec![
        glass_shadow(glass_white(0.8), 1.0, 0.0, 0.0, true),
        glass_shadow(
            glass_black(if dark { 0.22 } else { 0.14 }),
            1.0,
            2.0,
            0.0,
            false,
        ),
    ];
    if !dark {
        // A wide, faint ambient drop separates it from pale tracks.
        shadows.push(glass_shadow(glass_black(0.06), 2.0, 6.0, 0.0, false));
    }
    Plate {
        background: vertical_glass(hsla(0.0, 0.0, 1.0, 1.0), hsla(0.0, 0.0, 0.965, 1.0)),
        rim: glass_black(if dark { 0.12 } else { 0.11 }),
        shadows,
    }
}

pub(crate) fn light<E: Styled>(el: E, theme: &Theme, t: f32) -> E {
    light_plate(theme, t).apply(el)
}

pub(crate) fn thumb<E: Styled>(el: E, theme: &Theme) -> E {
    thumb_plate(theme).apply(el)
}
