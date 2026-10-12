//! Pure attachment-transport helpers (the layout's user-message parsing).
//! The implementation moved to `roboco-mobile-client::attachments` (ticket
//! 08: the send path and the byte cache live next to it in the data-plane
//! crate; `roboco-mobile` depends on `roboco-mobile-client`, never the
//! reverse). This re-export keeps the layout's
//! `crate::attachments::parse_user_message` import unchanged — ticket 05
//! anticipated exactly this move.

pub use roboco_mobile_client::attachments::*;
