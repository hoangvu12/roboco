import { describe, expect, it } from "vitest";
import {
  ATTACHMENT_ONLY_TEXT,
  ensureExtension,
  formatByBytes,
  formatByName,
  formatToMime,
  parseUserMessageImages,
  userMessageRailText,
  withAttachments,
  type AttachmentFormat,
} from "../src/lib/attachments";

describe("withAttachments", () => {
  it("appends the refs trailer with one path per line", () => {
    const out = withAttachments("look at these", ["/data/uploads/a.png"]);
    expect(out).toBe(
      `look at these\n\nAttached images (local files — open them to view):\n- /data/uploads/a.png`,
    );
  });

  it("uses the image-only placeholder when the prompt is empty", () => {
    const out = withAttachments("", ["/data/uploads/a.png"]);
    expect(out.startsWith(ATTACHMENT_ONLY_TEXT)).toBe(true);
    expect(out).toContain("- /data/uploads/a.png");
  });

  it("returns the original text when there are no paths", () => {
    expect(withAttachments("hello", [])).toBe("hello");
  });

  it("lists every path in send order, joined with newlines", () => {
    const out = withAttachments("all", ["/a.png", "/b.jpg", "/c.webp"]);
    const tail = out.split("\n\n")[1]!;
    expect(tail.split("\n").slice(1)).toEqual(["- /a.png", "- /b.jpg", "- /c.webp"]);
  });
});

describe("parseUserMessageImages", () => {
  it("returns the original text and no attachments when no trailer is present", () => {
    expect(parseUserMessageImages("plain prompt")).toEqual({
      text: "plain prompt",
      attachments: [],
    });
  });

  it("splits a message with one attachment into text + one entry", () => {
    const content = withAttachments("look", ["/data/uploads/x.png"]);
    const parsed = parseUserMessageImages(content);
    expect(parsed.text).toBe("look");
    expect(parsed.attachments).toEqual([
      { id: "0:/data/uploads/x.png", name: "x.png", path: "/data/uploads/x.png" },
    ]);
  });

  it("collapses the image-only placeholder to an empty visible text", () => {
    const content = withAttachments("", ["/a.png"]);
    const parsed = parseUserMessageImages(content);
    expect(parsed.text).toBe("");
    expect(parsed.attachments).toHaveLength(1);
  });

  it("is case-insensitive on the trailer header", () => {
    const parsed = parseUserMessageImages(
      "hi\n\nATTACHED IMAGES (local files — open them to view):\n- /p/q.png",
    );
    expect(parsed.attachments).toHaveLength(1);
    expect(parsed.attachments[0]!.path).toBe("/p/q.png");
  });

  it("leaves a trailer with no `- path` lines as plain text", () => {
    const content = "hi\n\nAttached images (local files — open them to view):\nnothing";
    expect(parseUserMessageImages(content).attachments).toEqual([]);
  });

  it("ignores non-`- ` prefixed lines mixed with valid refs", () => {
    const content =
      "intro\n\nAttached images (local files — open them to view):\nskip me\n- /a.png\nplain";
    const parsed = parseUserMessageImages(content);
    expect(parsed.attachments.map((entry) => entry.path)).toEqual(["/a.png"]);
  });

  it("round-trips through withAttachments", () => {
    const original = "look at these";
    const paths = ["/data/uploads/cat.png", "/uploads/dog.jpg"];
    const content = withAttachments(original, paths);
    const parsed = parseUserMessageImages(content);
    expect(parsed.text).toBe(original);
    expect(parsed.attachments.map((entry) => entry.path)).toEqual(paths);
    expect(parsed.attachments.map((entry) => entry.name)).toEqual(["cat.png", "dog.jpg"]);
  });
});

describe("userMessageRailText", () => {
  it("returns the prompt text when present", () => {
    expect(userMessageRailText("see the issue")).toBe("see the issue");
  });

  it("summarizes a single-image attachment-only send", () => {
    expect(userMessageRailText(withAttachments("", ["/a.png"]))).toBe("Attached image");
  });

  it("summarizes a multi-image attachment-only send", () => {
    expect(
      userMessageRailText(withAttachments("", ["/a.png", "/b.png", "/c.png"])),
    ).toBe("3 attached images");
  });

  it("falls back to the raw content when no trailer is found", () => {
    expect(userMessageRailText("plain")).toBe("plain");
  });
});

describe("ensureExtension", () => {
  const png: AttachmentFormat = "png";
  const jpg: AttachmentFormat = "jpg";

  it("leaves a name with a valid extension alone", () => {
    expect(ensureExtension("shot.png", png)).toBe("shot.png");
    expect(ensureExtension("archive.tar.gz", png)).toBe("archive.tar.gz");
  });

  it("appends an extension when one is missing", () => {
    expect(ensureExtension("image", png)).toBe("image.png");
  });

  it("replaces an extension that is too short to be a valid file ext", () => {
    expect(ensureExtension("photo.j", jpg)).toBe("photo.j.jpg");
  });
});

describe("formatByName / formatByBytes / formatToMime", () => {
  it("detects formats from common extensions, case-insensitively", () => {
    expect(formatByName("foo.PNG")).toBe("png");
    expect(formatByName("foo.jpeg")).toBe("jpg");
    expect(formatByName("foo.tiff")).toBe("tif");
    expect(formatByName("foo.txt")).toBeNull();
  });

  it("sniffs a PNG from its magic bytes", () => {
    const header = new Uint8Array([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 0x00, 0x00]);
    expect(formatByBytes(header)).toBe("png");
  });

  it("sniffs a JPEG from its magic bytes", () => {
    expect(formatByBytes(new Uint8Array([0xff, 0xd8, 0xff, 0xe0, 0x00]))).toBe("jpg");
  });

  it("sniffs a GIF from its magic bytes", () => {
    expect(formatByBytes(new Uint8Array([0x47, 0x49, 0x46, 0x38, 0x39, 0x61]))).toBe("gif");
  });

  it("sniffs a WebP from its magic bytes", () => {
    expect(
      formatByBytes(new Uint8Array([0x52, 0x49, 0x46, 0x46, 0x00, 0x00, 0x00, 0x00, 0x57, 0x45, 0x42, 0x50])),
    ).toBe("webp");
  });

  it("returns null for bytes with no recognisable signature", () => {
    expect(formatByBytes(new Uint8Array([0x00, 0x01, 0x02]))).toBeNull();
  });

  it("maps every format to a recognisable MIME", () => {
    const formats: AttachmentFormat[] = ["png", "jpg", "gif", "webp", "svg", "bmp", "tif"];
    for (const fmt of formats) {
      expect(formatToMime(fmt)).toMatch(/^image\//);
    }
  });
});