//! Resume on another account — the Limited chat's account failover (the
//! FFI half of orchestration v2 ticket 09): the records behind the
//! session screen's Limited banner and its account picker, plus the core
//! calls they run. The engine owns the whole state machine
//! (`crates/engine/src/account_failover.rs`); these are the Kotlin-facing
//! copies of its RPCs — every method safe from any thread, `async` ones
//! running on the client's own runtime and awaitable from Kotlin
//! coroutines directly.

use roboco_proto::RESUME_CONTINUE_PROMPT;

use super::CoreClient;
use super::CoreResult;
use super::accounts::AgentAccount;
use super::on_runtime;

// ── the records ────────────────────────────────────────────────────────────

/// The ranked switch targets for one Limited chat's "Resume on another
/// account": the harness's cached accounts, best first (the first is the
/// recommendation), plus its signed-out rows. Both lists empty leaves
/// only Resume (and Resume at reset).
#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct ResumeCandidates {
    /// The chat the list was asked for (the scoped id).
    pub chat_id: String,
    /// Wire harness id (`claude-code`, `codex`, …).
    pub harness: String,
    /// The account the chat's limit hit — what the picker is switching
    /// FROM. Never a candidate.
    pub limited_account_id: Option<String>,
    /// The candidate rows in rank order, best first. Usage windows ride
    /// the rows themselves.
    pub candidates: Vec<AgentAccount>,
    /// The harness's OTHER accounts that are signed out: never switch
    /// targets, but the picker lists them so a dead login is visible.
    pub signed_out: Vec<AgentAccount>,
}

/// The synchronous outcome of "Resume on another account": the account
/// switch and continuation dispatch the engine performed — or why it
/// could not. The continuation turn's own result arrives through the
/// chat's status.
#[derive(Debug, Clone, PartialEq, uniffi::Enum)]
pub enum ResumeSwitchOutcome {
    /// The switch landed and the continuation was dispatched: the chat
    /// resumes its harness session under `account_id`.
    Switched {
        account_id: String,
        /// The row's email (or display name) — the "Resumed on …" label.
        account_email: Option<String>,
    },
    /// No healthy candidate: every other account of the harness is
    /// limited, signed out, an API key, or unswitchable.
    NoCandidates,
    /// The switch itself failed; the previous account is untouched.
    Failed { reason: String },
}

impl From<roboco_proto::ResumeOnAccountOutcome> for ResumeSwitchOutcome {
    fn from(outcome: roboco_proto::ResumeOnAccountOutcome) -> Self {
        match outcome {
            roboco_proto::ResumeOnAccountOutcome::Switched {
                account_id,
                account_email,
            } => ResumeSwitchOutcome::Switched {
                account_id,
                account_email,
            },
            roboco_proto::ResumeOnAccountOutcome::NoCandidates => ResumeSwitchOutcome::NoCandidates,
            roboco_proto::ResumeOnAccountOutcome::Failed { reason } => {
                ResumeSwitchOutcome::Failed { reason }
            }
        }
    }
}

/// The proto harness enum's wire string (`"claude-code"`).
fn harness_wire(h: &roboco_proto::HarnessId) -> String {
    serde_json::to_value(h)
        .ok()
        .and_then(|v| v.as_str().map(str::to_owned))
        .unwrap_or_default()
}

impl From<roboco_proto::ResumeAccountCandidates> for ResumeCandidates {
    fn from(list: roboco_proto::ResumeAccountCandidates) -> Self {
        let into_row = |account: roboco_proto::AgentAccount| AgentAccount::from(account);
        Self {
            chat_id: list.chat_id,
            harness: harness_wire(&list.harness),
            limited_account_id: list.limited_account_id,
            candidates: list.candidates.into_iter().map(into_row).collect(),
            signed_out: list.signed_out.into_iter().map(into_row).collect(),
        }
    }
}

// ── the calls ──────────────────────────────────────────────────────────────

#[uniffi::export]
impl CoreClient {
    /// The ranked switch targets for a Limited chat ("Resume on another
    /// account"): the harness's cached accounts, best first, plus its
    /// signed-out rows. No network — the engine's cached rows.
    pub async fn list_resume_account_candidates(
        &self,
        chat_id: String,
    ) -> CoreResult<ResumeCandidates> {
        let client = self.client.clone();
        on_runtime(async move {
            client
                .resume_account_candidates(&chat_id)
                .await
                .map(Into::into)
        })
        .await
    }

    /// **Resume on another account**: switch the chat's harness to
    /// `account_id` (or the engine's recommendation when `None`), retire
    /// the chat's warm runtime and queue the continuation. A failed
    /// switch leaves the harness as it was.
    pub async fn resume_on_another_account(
        &self,
        chat_id: String,
        account_id: Option<String>,
    ) -> CoreResult<ResumeSwitchOutcome> {
        let client = self.client.clone();
        on_runtime(async move {
            client
                .resume_on_another_account(&chat_id, account_id.as_deref())
                .await
                .map(Into::into)
        })
        .await
    }

    /// Arm the chat's durable **Resume at reset** continuation: at the
    /// limit's reset time the engine sends the continue prompt like the
    /// manual Resume.
    pub async fn schedule_resume_at_reset(&self, chat_id: String) -> CoreResult<()> {
        let client = self.client.clone();
        on_runtime(async move { client.schedule_resume_at_reset(&chat_id).await }).await
    }

    /// Cancel the chat's pending Resume-at-reset continuation (a no-op
    /// when nothing is armed).
    pub async fn cancel_scheduled_resume(&self, chat_id: String) -> CoreResult<()> {
        let client = self.client.clone();
        on_runtime(async move { client.cancel_scheduled_resume(&chat_id).await }).await
    }
}

/// The continue prompt every resumed Limited chat sends — one shared
/// spelling across desktop, web and mobile so every resumed transcript
/// reads the same.
#[uniffi::export]
pub fn resume_continue_prompt() -> String {
    RESUME_CONTINUE_PROMPT.to_string()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn the_candidate_list_converts_its_rows() {
        let list: roboco_proto::ResumeAccountCandidates =
            serde_json::from_value(serde_json::json!({
                "chatId": "c1",
                "harness": "claude-code",
                "limitedAccountId": "slot-1",
                "candidates": [{
                    "id": "slot-2",
                    "harness": "claude-code",
                    "email": "b@example.com",
                    "planLabel": "Max 20x",
                    "active": false,
                    "usageWindows": [{
                        "label": "Session",
                        "usedFraction": 0.25,
                        "resetsAt": "2026-01-01T05:00:00Z",
                    }],
                    "switchable": true,
                }],
                "signedOut": [],
            }))
            .unwrap();
        let converted: ResumeCandidates = list.into();
        assert_eq!(converted.chat_id, "c1");
        assert_eq!(converted.harness, "claude-code");
        assert_eq!(converted.limited_account_id.as_deref(), Some("slot-1"));
        assert_eq!(converted.candidates.len(), 1);
        assert_eq!(converted.candidates[0].id, "slot-2");
        assert_eq!(converted.candidates[0].usage_windows.len(), 1);
        assert!(converted.signed_out.is_empty());
    }

    #[test]
    fn the_outcomes_convert_their_three_arms() {
        assert_eq!(
            ResumeSwitchOutcome::from(roboco_proto::ResumeOnAccountOutcome::Switched {
                account_id: "slot-2".into(),
                account_email: Some("b@example.com".into()),
            }),
            ResumeSwitchOutcome::Switched {
                account_id: "slot-2".into(),
                account_email: Some("b@example.com".into()),
            }
        );
        assert_eq!(
            ResumeSwitchOutcome::from(roboco_proto::ResumeOnAccountOutcome::NoCandidates),
            ResumeSwitchOutcome::NoCandidates
        );
        assert_eq!(
            ResumeSwitchOutcome::from(roboco_proto::ResumeOnAccountOutcome::Failed {
                reason: "probe refused".into()
            }),
            ResumeSwitchOutcome::Failed {
                reason: "probe refused".into()
            }
        );
    }

    #[test]
    fn the_continue_prompt_is_the_shared_spelling() {
        assert_eq!(resume_continue_prompt(), "Continue where you left off.");
    }
}
