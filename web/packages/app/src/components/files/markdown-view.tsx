import { Fragment, type ReactNode } from "react";
import {
  markdownLinkTarget,
  resolveWorkspacePath,
  type MdBlock,
  type MdInline,
} from "../../lib/markdown";

/**
 * Renders the parsed markdown block tree for the files preview. External
 * links open in a new tab; workspace-relative links open the target file
 * through `onOpenPath` (the desktop resolves them the same way); other
 * schemes render as plain text.
 */
export function MarkdownView({
  blocks,
  documentPath,
  onOpenPath,
}: {
  blocks: readonly MdBlock[];
  documentPath: string;
  onOpenPath?: (path: string) => void;
}) {
  return (
    <div className="markdown">
      {blocks.map((block, index) => (
        <MarkdownBlock key={index} block={block} documentPath={documentPath} onOpenPath={onOpenPath} />
      ))}
    </div>
  );
}

function MarkdownBlock({
  block,
  documentPath,
  onOpenPath,
}: {
  block: MdBlock;
  documentPath: string;
  onOpenPath?: (path: string) => void;
}): ReactNode {
  switch (block.kind) {
    case "heading": {
      const Tag = (`h${Math.min(6, Math.max(1, block.level))}`) as "h1" | "h2" | "h3" | "h4" | "h5" | "h6";
      return (
        <Tag>
          <Inlines inlines={block.inlines} documentPath={documentPath} onOpenPath={onOpenPath} />
        </Tag>
      );
    }
    case "paragraph":
      return (
        <p>
          <Inlines inlines={block.inlines} documentPath={documentPath} onOpenPath={onOpenPath} />
        </p>
      );
    case "code":
      return (
        <pre className="markdown-code">
          <code>{block.text}</code>
        </pre>
      );
    case "quote":
      return (
        <blockquote>
          {block.blocks.map((child, index) => (
            <MarkdownBlock key={index} block={child} documentPath={documentPath} onOpenPath={onOpenPath} />
          ))}
        </blockquote>
      );
    case "list": {
      const Tag = block.ordered ? "ol" : "ul";
      return (
        <Tag>
          {block.items.map((item, index) => (
            <li key={index}>
              {item.map((child, childIndex) => (
                <MarkdownBlock key={childIndex} block={child} documentPath={documentPath} onOpenPath={onOpenPath} />
              ))}
            </li>
          ))}
        </Tag>
      );
    }
    case "table":
      return (
        <table>
          <thead>
            <tr>
              {block.header.map((cell, index) => (
                <th key={index}>
                  <Inlines inlines={cell} documentPath={documentPath} onOpenPath={onOpenPath} />
                </th>
              ))}
            </tr>
          </thead>
          <tbody>
            {block.rows.map((row, index) => (
              <tr key={index}>
                {row.map((cell, cellIndex) => (
                  <td key={cellIndex}>
                    <Inlines inlines={cell} documentPath={documentPath} onOpenPath={onOpenPath} />
                  </td>
                ))}
              </tr>
            ))}
          </tbody>
        </table>
      );
    case "rule":
      return <hr />;
  }
}

function Inlines({
  inlines,
  documentPath,
  onOpenPath,
}: {
  inlines: readonly MdInline[];
  documentPath: string;
  onOpenPath?: (path: string) => void;
}): ReactNode {
  return inlines.map((inline, index) => {
    switch (inline.kind) {
      case "text":
        return <Fragment key={index}>{inline.text}</Fragment>;
      case "code":
        return <code key={index}>{inline.text}</code>;
      case "bold":
        return (
          <strong key={index}>
            <Inlines inlines={inline.children} documentPath={documentPath} onOpenPath={onOpenPath} />
          </strong>
        );
      case "italic":
        return (
          <em key={index}>
            <Inlines inlines={inline.children} documentPath={documentPath} onOpenPath={onOpenPath} />
          </em>
        );
      case "strike":
        return (
          <s key={index}>
            <Inlines inlines={inline.children} documentPath={documentPath} onOpenPath={onOpenPath} />
          </s>
        );
      case "image":
        // Workspace-relative images stay unresolved on web v1; absolute
        // http(s) sources render inline.
        if (/^https?:\/\//i.test(inline.src)) {
          return <img key={index} className="markdown-image" src={inline.src} alt={inline.alt} />;
        }
        return <Fragment key={index}>{inline.alt.length > 0 ? `[${inline.alt}]` : null}</Fragment>;
      case "link": {
        const target = markdownLinkTarget(inline.href);
        if (target.kind === "external") {
          return (
            <a key={index} href={target.href} target="_blank" rel="noreferrer noopener">
              <Inlines inlines={inline.children} documentPath={documentPath} onOpenPath={onOpenPath} />
            </a>
          );
        }
        if (target.kind === "workspace" && onOpenPath !== undefined) {
          return (
            <a
              key={index}
              href="#"
              onClick={(event) => {
                event.preventDefault();
                onOpenPath(resolveWorkspacePath(documentPath, target.path));
              }}
            >
              <Inlines inlines={inline.children} documentPath={documentPath} onOpenPath={onOpenPath} />
            </a>
          );
        }
        return (
          <Fragment key={index}>
            <Inlines inlines={inline.children} documentPath={documentPath} onOpenPath={onOpenPath} />
          </Fragment>
        );
      }
    }
  });
}
