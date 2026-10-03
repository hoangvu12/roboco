import { useState } from "react";
import type { NativeChildCompletion, NativeNotice } from "@roboco/proto";
import type { EngineClient } from "@roboco/engine-client";
import { childStatusLabel, noticeView, usageLine } from "../lib/native";
import { NativeBlobView } from "./native-blob";

function Completion({ completion }: { readonly completion: NativeChildCompletion }) {
  const run = completion.run;
  return (
    <div className="native-completion" data-testid="native-completion">
      <div className="native-notice-title">
        {completion.description.length > 0 ? completion.description : completion.handle}
        {" · "}
        {childStatusLabel(completion.status)}
      </div>
      <p className="native-notice-body">
        {completion.handle}
        {completion.attempt !== null ? `, attempt ${completion.attempt}` : ""}
        {run !== null ? `, ${run.agent}${run.model !== null ? ` on ${run.model}` : ""}` : ""}
      </p>
      {completion.resultPreview.length > 0 && (
        <pre className="native-pre" data-testid="native-completion-result">
          {completion.resultPreview}
        </pre>
      )}
      {completion.resultTruncated && (
        <p className="native-notice-body">The result is shortened here. Open the agent tab for the full outcome.</p>
      )}
      {completion.changedFiles.length > 0 && (
        <ul className="native-notice-body">
          {completion.changedFiles.map((file) => (
            <li key={file}>{file}</li>
          ))}
          {completion.omittedChangedFiles > 0 && <li>and {completion.omittedChangedFiles} more changed files</li>}
        </ul>
      )}
      {completion.changedFiles.length === 0 && completion.omittedChangedFiles > 0 && (
        <p className="native-notice-body">{completion.omittedChangedFiles} changed files are not listed here.</p>
      )}
      {completion.usage !== null && <p className="native-notice-body">{usageLine(completion.usage)}</p>}
      {run !== null && (
        <p className="native-notice-body" data-testid="native-completion-run">
          {run.turns} turns, {run.toolUses} tool uses, {run.tokens.toLocaleString("en-US")} tokens, {run.phase}
          {run.continued ? ", continued" : ""}
          {run.background ? ", background" : ""}
        </p>
      )}
    </div>
  );
}

/** One host notice, rendered honestly. A notice with a kept record offers it through the bounded blob view. */
export function NativeNoticeRow({ notice, client }: { readonly notice: NativeNotice; readonly client: EngineClient }) {
  const view = noticeView(notice);
  const [open, setOpen] = useState(false);
  return (
    <div className="native-notice" data-tone={view.tone} data-testid={`native-notice-${notice.notice}`}>
      <div className="native-notice-title">{view.title}</div>
      {view.body !== null && <p className="native-notice-body">{view.body}</p>}
      {notice.notice === "subagentCompletions" &&
        notice.completions.map((completion) => (
          <Completion key={`${completion.handle}#${completion.attempt ?? "-"}`} completion={completion} />
        ))}
      {view.detailRef !== null && (
        <>
          <div className="native-link-actions">
            <button type="button" className="btn btn-ghost" aria-expanded={open} onClick={() => setOpen(!open)}>
              {open ? "Hide the host record" : "Show the host record"}
            </button>
          </div>
          {open && (
            <NativeBlobView
              caller={client}
              refs={[view.detailRef]}
              totalBytes={null}
              filename={`${notice.notice}-${view.detailRef.split("/").pop() ?? "record"}.json`}
            />
          )}
        </>
      )}
    </div>
  );
}
