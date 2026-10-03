import { useMemo } from "react";
import { parseMarkdown } from "../lib/markdown";
import { MarkdownTreeView } from "./markdown";

/** Host-provided public Markdown (plans, answers, reports) in the transcript's own renderer. */
export function NativeMarkdown({ text }: { text: string }) {
  const tree = useMemo(() => parseMarkdown(text, false), [text]);
  return (
    <div className="native-markdown">
      <MarkdownTreeView tree={tree} />
    </div>
  );
}
