use base64::{engine::general_purpose::STANDARD, Engine};
use serde_json::Value;
use std::time::{Duration, Instant};

const CHUNK: usize = 12 * 1024;
pub const MAX_IMAGE: usize = 16 * 1024 * 1024;

pub struct ClipboardImage {
    pub receipt: String,
    pub mime: &'static str,
    pub bytes: Vec<u8>,
    size: usize,
    updated: Instant,
}

pub fn receipt(data: &Value) -> Option<&str> {
    data["receipt"]
        .as_str()
        .filter(|id| id.len() == 36 && id.bytes().all(|b| b.is_ascii_hexdigit() || b == b'-'))
}

impl ClipboardImage {
    pub fn new(data: &Value) -> Option<Self> {
        let size = data["size"]
            .as_u64()
            .filter(|n| (1..=MAX_IMAGE as u64).contains(n))? as usize;
        Some(Self {
            receipt: receipt(data)?.into(),
            mime: match data["mime"].as_str()? {
                "image/png" => "image/png",
                "image/jpeg" => "image/jpeg",
                "image/webp" => "image/webp",
                "image/gif" => "image/gif",
                _ => return None,
            },
            bytes: Vec::new(),
            size,
            updated: Instant::now(),
        })
    }

    pub fn expired(&self) -> bool {
        self.updated.elapsed() >= Duration::from_secs(12)
    }

    pub fn append(&mut self, data: &Value) -> Result<bool, ()> {
        if self.expired() || data["offset"].as_u64() != Some(self.bytes.len() as u64) {
            return Err(());
        }
        let encoded = data["bytes"]
            .as_str()
            .filter(|s| s.len() <= CHUNK * 4 / 3)
            .ok_or(())?;
        let bytes = STANDARD.decode(encoded).map_err(|_| ())?;
        if bytes.len() != (self.size - self.bytes.len()).min(CHUNK) || bytes.is_empty() {
            return Err(());
        }
        self.bytes.extend(bytes);
        self.updated = Instant::now();
        Ok(self.bytes.len() == self.size)
    }

    pub fn valid_header(&self) -> bool {
        match self.mime {
            "image/png" => self.bytes.starts_with(b"\x89PNG\r\n\x1a\n"),
            "image/jpeg" => self.bytes.starts_with(b"\xff\xd8\xff"),
            "image/webp" => {
                self.bytes.starts_with(b"RIFF") && self.bytes.get(8..12) == Some(b"WEBP")
            }
            "image/gif" => self.bytes.starts_with(b"GIF87a") || self.bytes.starts_with(b"GIF89a"),
            _ => false,
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    #[test]
    fn assembles_in_order_and_rejects_mismatched_offsets_and_mime() {
        let id = "c15a1915-e6da-4cdf-826c-e7d964f9d706";
        let mut image =
            ClipboardImage::new(&json!({"receipt": id, "mime": "image/png", "size": CHUNK + 1}))
                .unwrap();
        let mut first = vec![0; CHUNK];
        first[..8].copy_from_slice(b"\x89PNG\r\n\x1a\n");
        let head = json!({"offset": 0, "bytes": STANDARD.encode(first)});
        let tail = json!({"offset": CHUNK, "bytes": "AA=="});
        assert!(image.append(&tail).is_err());
        assert_eq!(image.append(&head), Ok(false));
        assert!(image.append(&head).is_err());
        assert_eq!(image.append(&tail), Ok(true));
        assert!(image.valid_header());
        image.mime = "image/gif";
        assert!(!image.valid_header());
        assert!(ClipboardImage::new(
            &json!({"receipt": id, "mime": "image/png", "size": MAX_IMAGE + 1})
        )
        .is_none());
    }
}
