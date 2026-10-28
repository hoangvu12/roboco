//! Cleanup of data that removed features left behind in the data dir.

use std::path::Path;

/// Where desktop dictation (removed in ci-speed 26) downloaded its Parakeet
/// model, about 600 MB, plus `*.part` files from an interrupted download.
const DICTATION_MODEL_DIR: &str = "models/parakeet-tdt-0.6b-v3-int8";

/// Deletes the dictation model directory if it is still there. Best effort:
/// only a failure is logged, and nothing else under `models/` is touched.
pub(crate) fn remove_dictation_model(data_dir: &Path) {
    let dir = data_dir.join(DICTATION_MODEL_DIR);
    match std::fs::remove_dir_all(&dir) {
        Ok(()) => {}
        Err(err) if err.kind() == std::io::ErrorKind::NotFound => {}
        Err(err) => {
            tracing::warn!(dir = %dir.display(), %err, "could not remove the old dictation model");
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn removes_only_the_dictation_model() {
        let data = tempfile::tempdir().unwrap();
        let model = data.path().join(DICTATION_MODEL_DIR);
        std::fs::create_dir_all(&model).unwrap();
        std::fs::write(model.join("encoder-model.int8.onnx"), b"weights").unwrap();
        std::fs::write(model.join("vocab.txt.part"), b"partial").unwrap();
        let other = data.path().join("models/other");
        std::fs::create_dir_all(&other).unwrap();
        std::fs::write(other.join("keep.bin"), b"keep").unwrap();
        std::fs::write(data.path().join("models/keep.txt"), b"keep").unwrap();

        remove_dictation_model(data.path());
        assert!(!model.exists());
        assert!(other.join("keep.bin").exists());
        assert!(data.path().join("models/keep.txt").exists());

        // Later startups find nothing to do.
        remove_dictation_model(data.path());
        assert!(other.join("keep.bin").exists());
    }

    #[test]
    fn a_data_dir_without_models_is_left_alone() {
        let data = tempfile::tempdir().unwrap();
        remove_dictation_model(data.path());
        assert!(!data.path().join("models").exists());
    }
}
