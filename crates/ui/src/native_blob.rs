//! Windowed reads of native tool and notice blobs.
//!
//! The engine serves a blob in windows of at most 1 MiB. A read accumulates
//! bytes and decodes once, so a character cut by a window edge is whole.
//! Two kinds of read never share a result: a preview stops at
//! `PREVIEW_CAP_BYTES` and says so on screen, and an export reads every byte of
//! every record and is what Save writes.

use std::time::Duration;

use gpui::BackgroundExecutor;
use roboco_engine::doc_host::ToolBlobWindow;
use roboco_proto::{NativeBlobSeries, NativeToolDetail};
use roboco_rpc::methods;

use crate::engine_registry::EngineTarget;
use crate::native::{self, BlobBytes, DetailSection, ExportPart};

const WINDOW_BYTES: u64 = 1024 * 1024;
const WINDOW_TIMEOUT: Duration = Duration::from_secs(20);
/// Bytes one inline preview reads across its record and every series chunk.
pub const PREVIEW_CAP_BYTES: usize = 8 * 1024 * 1024;

/// A blob read so far. `total` is the record's size as the engine reported it.
pub struct BlobRead {
    pub bytes: BlobBytes,
    pub total: u64,
    pub complete: bool,
}

/// One blob, window by window, until it ends or `budget` bytes are in.
pub async fn read_blob(
    engine: &EngineTarget,
    executor: &BackgroundExecutor,
    blob_ref: &str,
    budget: usize,
) -> Result<BlobRead, String> {
    let mut bytes = BlobBytes::default();
    let mut offset = 0u64;
    loop {
        let reply = crate::attachments::call_with_timeout(
            engine,
            executor,
            methods::FETCH_TOOL_BLOB,
            serde_json::json!({ "blobRef": blob_ref, "offset": offset, "maxBytes": WINDOW_BYTES }),
            WINDOW_TIMEOUT,
        )
        .await?;
        let window: ToolBlobWindow = serde_json::from_value(reply)
            .map_err(|err| format!("The host sent a blob window this app cannot read: {err}"))?;
        bytes.push(&window)?;
        let total = window.total_bytes;
        match window.next_offset {
            None => {
                return Ok(BlobRead {
                    bytes,
                    total,
                    complete: true,
                });
            }
            Some(_) if bytes.len() >= budget => {
                return Ok(BlobRead {
                    bytes,
                    total,
                    complete: false,
                });
            }
            Some(next) => offset = next,
        }
    }
}

/// Every byte of one blob.
pub async fn read_whole(
    engine: &EngineTarget,
    executor: &BackgroundExecutor,
    blob_ref: &str,
) -> Result<Vec<u8>, String> {
    let read = read_blob(engine, executor, blob_ref, usize::MAX).await?;
    Ok(read.bytes.as_bytes().to_vec())
}

/// Every chunk of a numbered series, joined in order.
async fn read_series_whole(
    engine: &EngineTarget,
    executor: &BackgroundExecutor,
    series: &NativeBlobSeries,
) -> Result<Vec<u8>, String> {
    let mut all = Vec::new();
    for blob_ref in native::series_refs(series) {
        all.extend(read_whole(engine, executor, &blob_ref).await?);
    }
    Ok(all)
}

/// A series' chunks within the remaining preview budget. True when the budget ran out.
async fn read_series_preview(
    engine: &EngineTarget,
    executor: &BackgroundExecutor,
    series: &NativeBlobSeries,
    budget: &mut usize,
) -> Result<(String, bool), String> {
    let mut all = BlobBytes::default();
    for blob_ref in native::series_refs(series) {
        if *budget == 0 {
            return Ok((all.into_text(), true));
        }
        let read = read_blob(engine, executor, &blob_ref, *budget).await?;
        *budget = budget.saturating_sub(read.bytes.len());
        all.push_raw(read.bytes.as_bytes());
        if !read.complete {
            return Ok((all.into_text(), true));
        }
    }
    Ok((all.into_text(), false))
}

/// The inline view of one tool detail. `cut` says, in the user's words, what
/// the preview left out; Save always exports the complete record.
pub struct DetailPreview {
    pub sections: Vec<DetailSection>,
    pub cut: Option<String>,
}

fn cut_notice(shown: usize, total: u64) -> String {
    format!(
        "This view shows the first {} of {}. Save exports the complete record.",
        native::format_bytes(shown as u64),
        native::format_bytes(total)
    )
}

/// The detail record and its series, bounded by `PREVIEW_CAP_BYTES`. A record
/// the cap cut is shown as stored with the cut said, never parsed as if whole.
pub async fn read_tool_detail_preview(
    engine: &EngineTarget,
    executor: &BackgroundExecutor,
    blob_ref: &str,
) -> Result<DetailPreview, String> {
    preview_within(engine, executor, blob_ref, PREVIEW_CAP_BYTES).await
}

async fn preview_within(
    engine: &EngineTarget,
    executor: &BackgroundExecutor,
    blob_ref: &str,
    cap: usize,
) -> Result<DetailPreview, String> {
    let mut budget = cap;
    let read = read_blob(engine, executor, blob_ref, budget).await?;
    let shown = read.bytes.len();
    budget = budget.saturating_sub(shown);
    if !read.complete {
        return Ok(DetailPreview {
            sections: vec![DetailSection {
                title: "Detail record, as stored".to_owned(),
                body: read.bytes.into_text(),
                markdown: false,
            }],
            cut: Some(cut_notice(shown, read.total)),
        });
    }
    let raw = read.bytes.into_text();
    let Ok(detail) = serde_json::from_str::<NativeToolDetail>(&raw) else {
        return Ok(DetailPreview {
            sections: vec![DetailSection {
                title: "Unrecognised detail record, shown as stored".to_owned(),
                body: raw,
                markdown: false,
            }],
            cut: None,
        });
    };
    let mut cut = false;
    let mut texts = [None, None];
    for (text, series) in texts.iter_mut().zip([&detail.progress, &detail.stream]) {
        if let Some(series) = series {
            let (read, stopped) =
                read_series_preview(engine, executor, series, &mut budget).await?;
            cut |= stopped;
            *text = Some(read);
        }
    }
    let [progress, stream] = texts;
    Ok(DetailPreview {
        sections: native::detail_sections(&detail, progress.as_deref(), stream.as_deref()),
        cut: cut.then(|| {
            format!(
                "This view stops at {} of streamed records. Save exports every record.",
                native::format_bytes(cap as u64)
            )
        }),
    })
}

/// The complete detail: the record exactly as stored, then every progress and
/// stream chunk, each its own section. A record that is not a tool detail is
/// exported as stored, alone.
pub async fn export_tool_detail(
    engine: &EngineTarget,
    executor: &BackgroundExecutor,
    blob_ref: &str,
) -> Result<Vec<u8>, String> {
    let record = read_whole(engine, executor, blob_ref).await?;
    let detail = serde_json::from_slice::<NativeToolDetail>(&record).ok();
    let mut series = Vec::new();
    for (what, entry) in [
        (
            "Progress lines (JSON lines)",
            detail.as_ref().and_then(|d| d.progress.as_ref()),
        ),
        (
            "Streamed output",
            detail.as_ref().and_then(|d| d.stream.as_ref()),
        ),
    ] {
        if let Some(entry) = entry {
            let bytes = read_series_whole(engine, executor, entry).await?;
            series.push((
                format!(
                    "{what}: {} ({} chunks, {} records)",
                    entry.blob_ref, entry.chunks, entry.records
                ),
                bytes,
            ));
        }
    }
    let mut parts = vec![ExportPart {
        label: if detail.is_some() {
            format!("Detail record: {blob_ref} (the host's public JSON as stored)")
        } else {
            format!("Unrecognised record: {blob_ref} (as stored)")
        },
        bytes: &record,
    }];
    parts.extend(series.iter().map(|(label, bytes)| ExportPart {
        label: label.clone(),
        bytes,
    }));
    Ok(native::compose_export("Mimir tool call detail", &parts))
}

/// A notice's record for the inline view: JSON pretty-printed, anything else as
/// stored. A record the cap cut is shown as stored with the cut said.
pub async fn read_notice_preview(
    engine: &EngineTarget,
    executor: &BackgroundExecutor,
    blob_ref: &str,
) -> Result<String, String> {
    let read = read_blob(engine, executor, blob_ref, PREVIEW_CAP_BYTES).await?;
    let shown = read.bytes.len();
    let total = read.total;
    if !read.complete {
        let mut text = read.bytes.into_text();
        text.push_str("\n\n");
        text.push_str(&cut_notice(shown, total));
        return Ok(text);
    }
    let raw = read.bytes.into_text();
    Ok(match serde_json::from_str::<serde_json::Value>(&raw) {
        Ok(value) => native::pretty(&value),
        Err(_) => raw,
    })
}

#[cfg(test)]
mod tests {
    use std::cell::RefCell;
    use std::collections::HashMap;
    use std::rc::Rc;

    use gpui::{AppContext as _, TestAppContext};
    use roboco_proto::{NativeToolKind, NativeToolPreview, NativeToolResultState, NativeToolView};

    use super::*;
    use crate::native_dock::scripted::{self, pump};
    use crate::state::AppState;

    /// Serves engine blobs in small windows, as `FetchToolBlob` does.
    fn serve(wire: &mut scripted::Wire, blobs: &HashMap<String, Vec<u8>>, window: usize) -> usize {
        use base64::Engine as _;
        let mut served = 0;
        for frame in wire.drain() {
            assert_eq!(frame.method.as_deref(), Some(methods::FETCH_TOOL_BLOB));
            let bytes = &blobs[frame.params["blobRef"].as_str().unwrap()];
            let offset = frame.params["offset"].as_u64().unwrap() as usize;
            let end = bytes.len().min(offset + window);
            wire.answer(
                &frame,
                serde_json::json!({
                    "encoding": "base64",
                    "text": base64::engine::general_purpose::STANDARD.encode(&bytes[offset..end]),
                    "offset": offset,
                    "totalBytes": bytes.len(),
                    "nextOffset": (end < bytes.len()).then_some(end),
                }),
            );
            served += 1;
        }
        served
    }

    fn view() -> NativeToolView {
        NativeToolView {
            name: "bash".into(),
            tool_call_id: "c1".into(),
            invocation_id: Some("p".into()),
            title: "Run tests".into(),
            running_title: None,
            kind: NativeToolKind::Shell,
            summary: None,
            locations: Vec::new(),
            result_state: NativeToolResultState::Normal,
            preview: NativeToolPreview::Full,
            semantic: None,
            quiet: false,
            group: None,
            subagent: None,
            progress: None,
            duration_ms: Some(10),
            detail_ref: Some("chat/p.native".into()),
            detail_bytes: None,
            child: None,
        }
    }

    fn drive<T: 'static>(
        runtime: &tokio::runtime::Runtime,
        wire: &mut scripted::Wire,
        blobs: &HashMap<String, Vec<u8>>,
        cx: &mut TestAppContext,
        read: impl std::future::Future<Output = T> + 'static,
    ) -> T {
        let out = Rc::new(RefCell::new(None));
        let slot = out.clone();
        cx.foreground_executor()
            .spawn(async move {
                *slot.borrow_mut() = Some(read.await);
            })
            .detach();
        for _ in 0..2000 {
            pump(runtime, cx);
            if let Some(value) = out.borrow_mut().take() {
                return value;
            }
            serve(wire, blobs, 512);
        }
        panic!("the read never finished");
    }

    fn engine(cx: &mut TestAppContext) -> (EngineTarget, scripted::Wire) {
        let app = cx.new(|_| AppState::new());
        let wire = scripted::attach(&app, cx);
        let engine = app.read_with(cx, |app, _| app.target_for_id("chat").unwrap());
        (engine, wire)
    }

    fn records() -> (HashMap<String, Vec<u8>>, Vec<u8>, Vec<u8>, Vec<u8>) {
        let progress = "\"step\"\n".repeat(200).into_bytes();
        let (first, second) = progress.split_at(700);
        let stream = "delta ".repeat(300).into_bytes();
        let detail = NativeToolDetail {
            name: "bash".into(),
            tool_call_id: "c1".into(),
            invocation_id: Some("p".into()),
            input: Some(serde_json::json!({"command": "cargo test"})),
            raw_input: Some("{\"command\":\"cargo test\"}".into()),
            is_error: Some(false),
            output: Some("ok ".repeat(900)),
            display_content: Vec::new(),
            details: None,
            output_profile: Some(serde_json::json!({"lines": 1})),
            compactions: vec![serde_json::json!({"reason": "size"})],
            progress: Some(NativeBlobSeries {
                blob_ref: "chat/p.progress".into(),
                chunks: 2,
                bytes: progress.len() as u64,
                records: 200,
            }),
            stream: Some(NativeBlobSeries {
                blob_ref: "chat/p.stream".into(),
                chunks: 1,
                bytes: stream.len() as u64,
                records: 300,
            }),
            view: view(),
        };
        // Spacing a stored record differently from serde's output proves the
        // export copies bytes instead of re-encoding them.
        let record = serde_json::to_string_pretty(&detail).unwrap().into_bytes();
        let blobs = HashMap::from([
            ("chat/p.native".to_owned(), record.clone()),
            ("chat/p.progress.000000".to_owned(), first.to_vec()),
            ("chat/p.progress.000001".to_owned(), second.to_vec()),
            ("chat/p.stream.000000".to_owned(), stream.clone()),
        ]);
        (blobs, record, progress, stream)
    }

    #[gpui::test]
    fn a_capped_preview_says_so_even_when_the_record_cannot_parse(cx: &mut TestAppContext) {
        let runtime = tokio::runtime::Builder::new_current_thread()
            .enable_all()
            .build()
            .unwrap();
        let _guard = runtime.enter();
        let (engine, mut wire) = engine(cx);
        let (blobs, record, _, _) = records();
        let executor = cx.executor();
        let preview = drive(&runtime, &mut wire, &blobs, cx, async move {
            preview_within(&engine, &executor, "chat/p.native", 1024).await
        })
        .unwrap();
        assert_eq!(preview.sections.len(), 1);
        assert_eq!(preview.sections[0].title, "Detail record, as stored");
        assert_eq!(preview.sections[0].body.as_bytes(), &record[..1024]);
        assert_eq!(
            preview.cut.as_deref(),
            Some(format!(
                "This view shows the first 1.0 KB of {}. Save exports the complete record.",
                native::format_bytes(record.len() as u64)
            ))
            .as_deref()
        );
    }

    #[gpui::test]
    fn save_exports_the_whole_record_and_every_series_chunk_regardless_of_the_preview_cap(
        cx: &mut TestAppContext,
    ) {
        let runtime = tokio::runtime::Builder::new_current_thread()
            .enable_all()
            .build()
            .unwrap();
        let _guard = runtime.enter();
        let (engine, mut wire) = engine(cx);
        let (blobs, record, progress, stream) = records();
        let executor = cx.executor();
        let preview_engine = engine.clone();
        let preview_executor = executor.clone();
        let preview = drive(&runtime, &mut wire, &blobs, cx, async move {
            preview_within(
                &preview_engine,
                &preview_executor,
                "chat/p.native",
                record_len_plus(300),
            )
            .await
        })
        .unwrap();
        assert!(
            preview
                .cut
                .as_deref()
                .is_some_and(|cut| cut.contains("Save exports every record")),
            "the series ran past the preview budget and the view says so"
        );
        let export = drive(&runtime, &mut wire, &blobs, cx, async move {
            export_tool_detail(&engine, &executor, "chat/p.native").await
        })
        .unwrap();
        let expected = native::compose_export(
            "Mimir tool call detail",
            &[
                ExportPart {
                    label: "Detail record: chat/p.native (the host's public JSON as stored)".into(),
                    bytes: &record,
                },
                ExportPart {
                    label: "Progress lines (JSON lines): chat/p.progress (2 chunks, 200 records)"
                        .into(),
                    bytes: &progress,
                },
                ExportPart {
                    label: "Streamed output: chat/p.stream (1 chunks, 300 records)".into(),
                    bytes: &stream,
                },
            ],
        );
        assert_eq!(
            String::from_utf8(export).unwrap(),
            String::from_utf8(expected).unwrap()
        );
    }

    fn record_len_plus(extra: usize) -> usize {
        records().1.len() + extra
    }
}
