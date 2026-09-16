import { memo, useMemo, useState, type ReactNode } from "react";
import type { Block, BlockTree, InlineRun, TableAlign } from "../lib/markdown";
import { PENDING_LINK_URL } from "../lib/markdown";
import { highlightCode, splitTokenLines, type SyntaxRole } from "../lib/syntax";

/**
 * Renders the parsed markdown model (`../lib/markdown.ts`) — one React
 * element tree per top-level block, so transcript rows re-render only the
 * block whose bytes changed. Colors come from `--rb-*` custom properties
 * (`md-*`/`tk-*` classes in app.css); no color is hardcoded here.
 */

export const MarkdownBlockView = memo(function MarkdownBlockView({ block }: { block: Block }) {
  return <>{renderBlock(block)}</>;
});

/** A whole tree (assistant text part, thought detail) block by block. */
export function MarkdownTreeView({ tree }: { tree: BlockTree }) {
  return (
    <>
      {tree.blocks.map((top, ix) => (
        <MarkdownBlockView key={ix} block={top.block} />
      ))}
    </>
  );
}

function renderBlock(block: Block): ReactNode {
  switch (block.kind) {
    case "paragraph":
      return (
        <p className="md-p">
          <InlineRuns runs={block.runs} />
        </p>
      );
    case "heading": {
      const content = <InlineRuns runs={block.runs} />;
      switch (Math.min(6, Math.max(1, block.level))) {
        case 1:
          return <h1 className="md-h md-h1">{content}</h1>;
        case 2:
          return <h2 className="md-h md-h2">{content}</h2>;
        case 3:
          return <h3 className="md-h md-h3">{content}</h3>;
        case 4:
          return <h4 className="md-h md-h4">{content}</h4>;
        case 5:
          return <h5 className="md-h md-h5">{content}</h5>;
        default:
          return <h6 className="md-h md-h6">{content}</h6>;
      }
    }
    case "codeBlock":
      return <CodeBlock code={block.code} language={block.language} />;
    case "blockQuote":
      return (
        <blockquote className="md-quote">
          {block.children.map((child, ix) => (
            <MarkdownBlockView key={ix} block={child} />
          ))}
        </blockquote>
      );
    case "list": {
      const items = block.items.map((item, ix) => (
        <li key={ix} className={item.checked !== null ? "md-task" : undefined}>
          {item.checked !== null && (
            <span className={`md-checkbox ${item.checked ? "md-checkbox-done" : ""}`} aria-hidden>
              {item.checked ? "☑" : "☐"}
            </span>
          )}
          {item.blocks.map((child, childIx) => (
            <MarkdownBlockView key={childIx} block={child} />
          ))}
        </li>
      ));
      return block.orderedStart !== null ? (
        <ol className="md-list" start={block.orderedStart}>
          {items}
        </ol>
      ) : (
        <ul className="md-list">{items}</ul>
      );
    }
    case "table":
      return (
        <div className="md-table-wrap">
          <table className="md-table">
            <thead>
              <tr>
                {block.header.map((cell, ix) => (
                  <th key={ix} style={{ textAlign: alignCss(block.align[ix]) }}>
                    <InlineRuns runs={cell} />
                  </th>
                ))}
              </tr>
            </thead>
            <tbody>
              {block.rows.map((row, rowIx) => (
                <tr key={rowIx}>
                  {row.map((cell, cellIx) => (
                    <td key={cellIx} style={{ textAlign: alignCss(block.align[cellIx]) }}>
                      <InlineRuns runs={cell} />
                    </td>
                  ))}
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      );
    case "rule":
      return <hr className="md-rule" />;
  }
}

function alignCss(align: TableAlign | undefined): "left" | "center" | "right" {
  return align ?? "left";
}

function InlineRuns({ runs }: { runs: readonly InlineRun[] }) {
  return (
    <>
      {runs.map((run, ix) => (
        <InlineRunView key={ix} run={run} />
      ))}
    </>
  );
}

function InlineRunView({ run }: { run: InlineRun }) {
  const style = run.style;
  let content: ReactNode = run.text;
  if (style.code) {
    content = <code className="md-code">{content}</code>;
  }
  if (style.bold) {
    content = <strong>{content}</strong>;
  }
  if (style.italic) {
    content = <em>{content}</em>;
  }
  if (style.strikethrough) {
    content = <s>{content}</s>;
  }
  if (style.link !== null && style.link !== undefined) {
    // A mended link whose URL is still streaming renders styled but inert —
    // the settling URL must not collapse the line (mend.rs PENDING_LINK_URL).
    if (style.link === PENDING_LINK_URL) {
      content = <span className="md-link md-link-pending">{content}</span>;
    } else {
      content = (
        <a className="md-link" href={style.link} target="_blank" rel="noreferrer noopener">
          {content}
        </a>
      );
    }
  }
  return <>{content}</>;
}

/** A fenced code block: syntax tokens colored from theme roles + copy. */
export function CodeBlock({ code, language }: { code: string; language: string | null }) {
  const [copied, setCopied] = useState(false);
  const lines = useMemo(() => splitTokenLines(highlightCode(code, language)), [code, language]);

  const copy = (): void => {
    const clipboard = (navigator as Navigator | undefined)?.clipboard;
    if (clipboard === undefined) {
      return;
    }
    void clipboard.writeText(code).then(() => {
      setCopied(true);
      setTimeout(() => setCopied(false), 1200);
    });
  };

  return (
    <div className="md-codeblock">
      <button type="button" className="md-copy" onClick={copy}>
        {copied ? "Copied" : "Copy"}
      </button>
      <pre className="md-pre">
        <code>
          {lines.map((line, ix) => (
            <span key={ix} className="md-codeline">
              {line.map((token, tokenIx) => (
                <SyntaxTokenView key={tokenIx} text={token.text} role={token.role} />
              ))}
              {"\n"}
            </span>
          ))}
        </code>
      </pre>
    </div>
  );
}

function SyntaxTokenView({ text, role }: { text: string; role: SyntaxRole | null }) {
  if (role === null) {
    return <>{text}</>;
  }
  return <span className={`tk-${role}`}>{text}</span>;
}
