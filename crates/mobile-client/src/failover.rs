//! Resume on another account — the phone-side wrapper of the Limited
//! chat's account failover (orchestration v2, ticket 09, the mobile half
//! of the surface the desktop and web already ship).
//!
//! The engine owns the whole state machine (`crates/engine/src/
//! account_failover.rs`): it ranks the cached harness accounts, probes
//! the chosen one, activates it, retires the chat's warm runtime and
//! queues the continuation — a failure restores the previous account.
//! This module is the thin phone-side RPC wrapper: resolve the chat's
//! engine, call `ListResumeAccountCandidates` / `ResumeOnAnotherAccount`
//! (the raw chat id on the wire, the scoped one in the API — the same
//! split every chat-scoped call here makes), plus the two `Mutate` ops
//! that arm and cancel the chat's durable **Resume at reset**
//! continuation (ticket 07) the Limited banner's other pills drive.

use roboco_proto::{ResumeAccountCandidates, ResumeOnAccountOutcome};

use crate::ClientError;

use crate::Client;

impl Client {
    /// The ranked switch targets for a Limited chat ("Resume on another
    /// account"): the harness's cached accounts, best first (the first
    /// is the recommendation), plus its signed-out rows. Both lists empty
    /// leaves only Resume (and Resume at reset). No network — the
    /// engine's cached rows.
    pub async fn resume_account_candidates(
        &self,
        chat_id: &str,
    ) -> Result<ResumeAccountCandidates, ClientError> {
        let (handle, raw) = self.resolve(chat_id)?;
        let value = handle
            .call(
                roboco_rpc::methods::LIST_RESUME_ACCOUNT_CANDIDATES,
                serde_json::json!({ "chatId": raw }),
            )
            .await?;
        serde_json::from_value(value).map_err(|error| ClientError::HostError {
            message: format!("undecodable resume account candidates: {error}"),
        })
    }

    /// **Resume on another account**: switch the chat's harness to
    /// `account_id` (or the engine's recommendation when `None`), retire
    /// the chat's warm runtime and queue the continuation. The outcome is
    /// synchronous; the continuation turn's own result arrives through the
    /// chat's status. A failed switch leaves the harness as it was.
    pub async fn resume_on_another_account(
        &self,
        chat_id: &str,
        account_id: Option<&str>,
    ) -> Result<ResumeOnAccountOutcome, ClientError> {
        let (handle, raw) = self.resolve(chat_id)?;
        let mut params = serde_json::json!({ "chatId": raw });
        if let Some(id) = account_id {
            params["accountId"] = serde_json::Value::String(id.to_owned());
        }
        let value = handle
            .call(roboco_rpc::methods::RESUME_ON_ANOTHER_ACCOUNT, params)
            .await?;
        serde_json::from_value(value).map_err(|error| ClientError::HostError {
            message: format!("undecodable resume-on-account outcome: {error}"),
        })
    }

    /// Arm the chat's durable **Resume at reset** continuation: at the
    /// limit's reset time the engine sends the continue prompt like the
    /// manual Resume. Requires a Limited chat with a reset time still
    /// ahead (the engine's own guards answer the rest).
    pub async fn schedule_resume_at_reset(&self, chat_id: &str) -> Result<(), ClientError> {
        mutate_chat(self, chat_id, "scheduleResumeAtReset").await
    }

    /// Cancel the chat's pending Resume-at-reset continuation. A no-op
    /// when nothing is armed.
    pub async fn cancel_scheduled_resume(&self, chat_id: &str) -> Result<(), ClientError> {
        mutate_chat(self, chat_id, "cancelScheduledResume").await
    }
}

/// One chat-scoped `Mutate` op: the wire's raw chat id, `Ok` on any
/// successful reply (the ops answer `{ok: true}`; an error crosses as
/// the RPC's own `ClientError`).
async fn mutate_chat(client: &Client, chat_id: &str, op: &'static str) -> Result<(), ClientError> {
    let (handle, raw) = client.resolve(chat_id)?;
    handle
        .call(
            roboco_rpc::methods::MUTATE,
            serde_json::json!({ "op": op, "chatId": raw }),
        )
        .await?;
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    /// The wire shapes the FFI and Kotlin decode: the candidate list is
    /// camelCase with optional `limitedAccountId` and `signedOut`; the
    /// outcome is tagged with `type` in camelCase.
    #[test]
    fn the_failover_replies_decode() {
        let list: ResumeAccountCandidates = serde_json::from_value(serde_json::json!({
            "chatId": "c1",
            "harness": "claude-code",
            "limitedAccountId": "slot-1",
            "candidates": [{
                "id": "slot-2",
                "harness": "claude-code",
                "email": "b@example.com",
                "planLabel": "Max 20x",
                "active": false,
                "usageWindows": [
                    { "label": "Session", "usedFraction": 0.25, "resetsAt": "2026-01-01T05:00:00Z" },
                ],
                "switchable": true,
            }],
            "signedOut": [{
                "id": "slot-3",
                "harness": "claude-code",
                "email": "c@example.com",
                "active": false,
                "signInState": "signed-out",
                "switchable": true,
            }],
        }))
        .unwrap();
        assert_eq!(list.chat_id, "c1");
        assert_eq!(list.limited_account_id.as_deref(), Some("slot-1"));
        assert_eq!(list.candidates.len(), 1);
        assert_eq!(list.candidates[0].email.as_deref(), Some("b@example.com"));
        assert_eq!(list.signed_out.len(), 1);
        // An omitted signedOut list and limitedAccountId decode too.
        let bare: ResumeAccountCandidates = serde_json::from_value(serde_json::json!({
            "chatId": "c2",
            "harness": "codex",
            "candidates": [],
        }))
        .unwrap();
        assert!(bare.candidates.is_empty());
        assert!(bare.signed_out.is_empty());
        assert_eq!(bare.limited_account_id, None);

        let switched: ResumeOnAccountOutcome = serde_json::from_value(serde_json::json!({
            "type": "switched",
            "accountId": "slot-2",
            "accountEmail": "b@example.com",
        }))
        .unwrap();
        assert_eq!(
            switched,
            ResumeOnAccountOutcome::Switched {
                account_id: "slot-2".into(),
                account_email: Some("b@example.com".into()),
            }
        );
        assert_eq!(
            serde_json::from_value::<ResumeOnAccountOutcome>(
                serde_json::json!({ "type": "noCandidates" })
            )
            .unwrap(),
            ResumeOnAccountOutcome::NoCandidates
        );
        assert_eq!(
            serde_json::from_value::<ResumeOnAccountOutcome>(
                serde_json::json!({ "type": "failed", "reason": "probe refused" })
            )
            .unwrap(),
            ResumeOnAccountOutcome::Failed {
                reason: "probe refused".into()
            }
        );
    }
}
