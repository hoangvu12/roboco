//! Engine file RPCs the composer drives: `SearchFiles` (the @-mention
//! picker's data) and `FetchToolBlob` (a tool part's full output by its
//! doc-resident ref).

use crate::engines::EngineHandle;
use crate::ClientError;

/// A workspace-relative file or directory match for `@` mentions.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct FileMatch {
    pub path: String,
    pub is_dir: bool,
}

/// The mention picker's query: `SearchFiles {query, chatId?, spaceId?}`
/// (scoped ids on this side — the wire routing decodes them). Retries once
/// after 250 ms on a transport error (web composer precedent: the picker is
/// typed-ahead, and a transient blip must not blank the list).
pub async fn search_files(
    handle: &EngineHandle,
    chat_id: Option<&str>,
    space_id: Option<&str>,
    query: &str,
) -> Result<Vec<FileMatch>, ClientError> {
    let params = |chat_id: Option<&str>, space_id: Option<&str>| {
        serde_json::json!({
            "query": query,
            "chatId": chat_id,
            "spaceId": space_id,
        })
    };
    let first = handle
        .call(
            roboco_rpc::methods::SEARCH_FILES,
            params(chat_id, space_id),
        )
        .await;
    let value = match first {
        Ok(value) => value,
        Err(error @ ClientError::HostUnavailable { .. }) => {
            // One retry after a short delay; a second failure surfaces.
            tokio::time::sleep(std::time::Duration::from_millis(250)).await;
            handle
                .call(
                    roboco_rpc::methods::SEARCH_FILES,
                    params(chat_id, space_id),
                )
                .await
                .map_err(|_| error)?
        }
        Err(error) => return Err(error),
    };
    let rows: Vec<roboco_proto::FileSearchMatch> =
        serde_json::from_value(value).map_err(|error| ClientError::HostError {
            message: format!("undecodable file search reply: {error}"),
        })?;
    Ok(rows
        .into_iter()
        .map(|row| FileMatch {
            path: row.path,
            is_dir: row.is_dir,
        })
        .collect())
}

/// Fetch a tool part's full output by its `outputRef` / `diffRef` blob ref,
/// verbatim from the part. A missing ref maps to the engine's
/// "tool output is not stored on this engine" error.
pub async fn fetch_tool_blob(handle: &EngineHandle, blob_ref: &str) -> Result<String, ClientError> {
    let value = handle
        .call(
            roboco_rpc::methods::FETCH_TOOL_BLOB,
            serde_json::json!({ "blobRef": blob_ref }),
        )
        .await?;
    Ok(value
        .get("text")
        .and_then(serde_json::Value::as_str)
        .map(str::to_owned)
        .unwrap_or_default())
}

impl crate::Client {
    /// Files matching `query` in the chat's (or project's) workspace, for
    /// composer `@` mentions. `device_id` resolves to the engine (07's
    /// `DeviceView` field).
    pub async fn search_files(
        &self,
        device_id: &str,
        chat_id: Option<String>,
        space_id: Option<String>,
        query: &str,
    ) -> Result<Vec<FileMatch>, ClientError> {
        let handle = crate::catalog_live::resolve_engine(&self.inner_arc(), device_id)?;
        search_files(
            &handle,
            chat_id.as_deref(),
            space_id.as_deref(),
            query,
        )
        .await
    }
}
