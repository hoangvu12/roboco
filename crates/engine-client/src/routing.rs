//! Resolve a request owner before asynchronous work starts.

use crate::registry::{EngineKey, ScopedId};
use roboco_rpc::RpcError;

pub fn raw_id(id: &str) -> Result<String, RpcError> {
    Ok(ScopedId::parse(id)?.raw_id)
}

/// Only RPC envelope identity fields cross this boundary. Text, paths, model
/// options, tools, and user JSON remain byte-for-byte unchanged.
pub fn wire_params(
    owner: &EngineKey,
    method: &str,
    mut params: serde_json::Value,
) -> Result<serde_json::Value, RpcError> {
    fn decode(owner: &EngineKey, value: &mut serde_json::Value) -> Result<(), RpcError> {
        let Some(id) = value.as_str() else {
            return Ok(());
        };
        let parsed = ScopedId::parse(id)?;
        if ScopedId::is_scoped(id) && &parsed.engine != owner {
            return Err(RpcError::Failed(
                "Request identity belongs to another engine".into(),
            ));
        }
        *value = serde_json::Value::String(parsed.raw_id);
        Ok(())
    }
    fn fields(
        owner: &EngineKey,
        object: &mut serde_json::Map<String, serde_json::Value>,
    ) -> Result<(), RpcError> {
        for field in [
            "chatId",
            "sourceChatId",
            "spaceId",
            "deviceId",
            "checkoutId",
            "expectedCheckoutId",
            "docId",
            "parentChatId",
            "targetDeviceId",
        ] {
            if let Some(value) = object.get_mut(field) {
                decode(owner, value)?;
            }
        }
        // Routing ends at the socket; no engine forwards this request.
        object.remove("targetDeviceId");
        Ok(())
    }
    if let Some(object) = params.as_object_mut() {
        fields(owner, object)?;
        if let Some(target) = object
            .get_mut("target")
            .and_then(serde_json::Value::as_object_mut)
        {
            fields(owner, target)?;
        }
        if method == roboco_rpc::methods::MUTATE {
            if let Some(id) = object.get_mut("id") {
                decode(owner, id)?;
            }
        }
    }
    Ok(params)
}

/// Checkout diff streams are engine-local; only their row identity is projected.
pub fn scope_checkout_frame(owner: &EngineKey, mut frame: serde_json::Value) -> serde_json::Value {
    fn row(owner: &EngineKey, value: &mut serde_json::Value) {
        if let Some(object) = value.as_object_mut() {
            for field in ["checkoutId", "deviceId"] {
                if let Some(value) = object.get_mut(field) {
                    if let Some(id) = value.as_str() {
                        *value = serde_json::Value::String(ScopedId::encode(owner, id));
                    }
                }
            }
        }
    }
    match &mut frame {
        serde_json::Value::Array(rows) => rows.iter_mut().for_each(|value| row(owner, value)),
        value => row(owner, value),
    }
    frame
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    #[test]
    fn wire_boundary_decodes_only_request_identity() {
        let owner = EngineKey("engine-b".into());
        let id = ScopedId::encode(&owner, "same-chat");
        let params = json!({"chatId": id, "targetDeviceId": ScopedId::encode(&owner, "device"),
            "text": id, "options": {"chatId": id}, "path": id,
            "target": {"chatId": id}});
        let wire = wire_params(&owner, "ReadWorkspaceFile", params.clone()).unwrap();
        assert_eq!(wire["chatId"], "same-chat");
        assert_eq!(wire["target"]["chatId"], "same-chat");
        assert!(wire.get("targetDeviceId").is_none());
        for key in ["text", "options", "path"] {
            assert_eq!(wire[key], params[key]);
        }
    }

    #[test]
    fn wire_boundary_decodes_source_chat_id() {
        let owner = EngineKey("engine-b".into());
        let scoped = ScopedId::encode(&owner, "side-chat");
        // A scoped source chat id is decoded back to the owner's raw id, so a
        // remote engine's ForkSideChat reaches it with local ids again (the
        // TS twin has always decoded this field).
        let wire = wire_params(
            &owner,
            "ForkSideChat",
            json!({"chatId": scoped, "sourceChatId": scoped}),
        )
        .unwrap();
        assert_eq!(wire["chatId"], "side-chat");
        assert_eq!(wire["sourceChatId"], "side-chat");
        // Raw ids pass through unchanged — local desktop requests are identical.
        let wire =
            wire_params(&owner, "ForkSideChat", json!({"sourceChatId": "raw-chat"})).unwrap();
        assert_eq!(wire["sourceChatId"], "raw-chat");
        // A scoped id from a foreign engine is rejected client-side.
        let foreign = ScopedId::encode(&EngineKey("engine-a".into()), "side-chat");
        assert!(wire_params(&owner, "ForkSideChat", json!({"sourceChatId": foreign})).is_err());
    }

    #[test]
    fn wire_boundary_rejects_another_engine_identity() {
        let owner = EngineKey("engine-b".into());
        let foreign = ScopedId::encode(&EngineKey("engine-a".into()), "same-chat");
        assert!(wire_params(&owner, "QueueCommand", json!({"chatId": foreign})).is_err());
        let local_reserved = ScopedId::encode(&EngineKey::local(), "engine:v1:literal-id");
        assert!(wire_params(&owner, "QueueCommand", json!({"chatId": local_reserved})).is_err());
    }
}
