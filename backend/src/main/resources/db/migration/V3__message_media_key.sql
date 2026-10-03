-- Media lives in S3, not in Postgres. Only the object key is stored, so the column is nullable:
-- a TEXT message has no media, and a media message may or may not carry a caption in `content`.
ALTER TABLE messages ADD COLUMN media_key VARCHAR(512);

-- Read paths filter and sort on (chat_id, created_at, id); nothing here needs a new index because
-- media is never queried *by* key. Keys are authorised through the owning message instead.
COMMENT ON COLUMN messages.media_key IS
  'S3 object key for IMAGE/VIDEO/AUDIO messages; null for TEXT. Never exposed to clients directly - '
  'they request a short-lived download URL via POST /api/media/download-url.';