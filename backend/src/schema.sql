-- GoPreach backend schema (MySQL 8 / MariaDB 10.5+).
--
-- The app's offline cache and outbox already treat every record as "collection + id + JSON", so the server is a
-- versioned document store with a global change sequence. That lets phones sync with two calls:
--   push  = send queued writes,
--   pull  = "everything changed since sequence N" (deletes included, as tombstones).
-- Typed/relational tables can be layered on later without changing the app contract.

CREATE TABLE IF NOT EXISTS documents (
  collection      VARCHAR(255) NOT NULL,
  doc_id          VARCHAR(190) NOT NULL,
  data            JSON         NOT NULL,
  version         INT          NOT NULL DEFAULT 1,
  seq             BIGINT       NOT NULL,           -- global change sequence (cursor for pull)
  deleted         TINYINT(1)   NOT NULL DEFAULT 0, -- tombstone, so pulls can tell phones to remove it
  congregation_id VARCHAR(190) NULL,               -- denormalized for read filtering
  updated_at      BIGINT       NOT NULL,
  updated_by      VARCHAR(190) NULL,
  PRIMARY KEY (collection, doc_id),
  KEY idx_seq (seq),
  KEY idx_collection_seq (collection, seq),
  KEY idx_congregation (congregation_id, seq)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- One row holding the last issued sequence number. It is locked inside each write transaction, so sequence numbers are
-- issued in commit order and a puller can never skip a change.
CREATE TABLE IF NOT EXISTS counters (
  name VARCHAR(32) NOT NULL PRIMARY KEY,
  value BIGINT NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT IGNORE INTO counters (name, value) VALUES ('seq', 0);

-- Uploaded files (images). Bytes live on disk; this row is the index.
CREATE TABLE IF NOT EXISTS files (
  id          VARCHAR(64)  NOT NULL PRIMARY KEY,
  owner       VARCHAR(190) NOT NULL,
  mime        VARCHAR(100) NOT NULL,
  size_bytes  INT          NOT NULL,
  created_at  BIGINT       NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
