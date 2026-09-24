# 01 — Diff empty states + checkout-folder normalization

**What to build:** A web client user opening the Changes pane on a chat whose diff never resolves
sees the truth instead of an eternal "Preparing diff…" spinner: whether the chat's checkout isn't
a git repository (or has no checkout folder), the chat's diffs live on its own device (each
engine tracks only its own device's chats), or the diff watch genuinely hasn't delivered yet.
Matching a diff frame to a chat also stops failing on Windows verbatim path prefixes, so the
fallback folder match works when a chat row lacks a checkout id.

**Blocked by:** None — can start immediately.

**Status:** ready-for-agent

- [ ] A chat with a non-git checkout folder (or no checkout folder) shows an explicit message
      naming that condition, not the preparing spinner.
- [ ] A chat hosted on another device shows an explicit "diffs live on its own device" message.
- [ ] A watch that hasn't delivered its first item yet still shows the loading state.
- [ ] Checkout-folder matching normalizes Windows verbatim (`\\?\`) prefixes; the fallback match
      succeeds when the chat row lacks a checkout id — covered by a regression test that fails
      before the fix.
- [ ] The existing watch-error banner and scoped-error notices keep working unchanged.
- [ ] The availability classification is a pure function, unit-tested; the diff store's behavior
      against a scripted fake caller follows the engine-client fake-server pattern.
- [ ] The existing Changes surface render smoke keeps passing.
- [ ] The browser smoke harness's no-checkout chat now renders the legible empty state.
