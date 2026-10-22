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

-- Circuit Overseer File Manager (the bytes are in file_blobs under "co-files/<owner>/<id>") and Previous Manual Record.
-- Also created on first start by MysqlCoData.migrate (coStore.js). None of this is part of the sync API.
CREATE TABLE IF NOT EXISTS co_folders (
  id VARCHAR(40) NOT NULL PRIMARY KEY, owner VARCHAR(190) NOT NULL, name VARCHAR(200) NOT NULL, parent_id VARCHAR(40) NULL,
  created_at BIGINT NOT NULL, updated_at BIGINT NOT NULL, deleted_at BIGINT NULL, deleted_with VARCHAR(40) NULL, KEY idx_owner (owner, parent_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS co_files (
  id VARCHAR(40) NOT NULL PRIMARY KEY, owner VARCHAR(190) NOT NULL, name VARCHAR(255) NOT NULL, ext VARCHAR(10) NOT NULL, mime VARCHAR(100) NOT NULL,
  size_bytes BIGINT NOT NULL, folder_id VARCHAR(40) NULL, created_at BIGINT NOT NULL, updated_at BIGINT NOT NULL, deleted_at BIGINT NULL, deleted_with VARCHAR(40) NULL,
  KEY idx_owner (owner, folder_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS co_manual_imports (
  id VARCHAR(40) NOT NULL PRIMARY KEY, owner VARCHAR(190) NOT NULL, congregation_id VARCHAR(190) NOT NULL, congregation_name VARCHAR(255) NOT NULL, remarks VARCHAR(500) NOT NULL DEFAULT '',
  file_name VARCHAR(255) NOT NULL, sheets JSON NOT NULL, content_hash CHAR(64) NOT NULL, created_at BIGINT NOT NULL,
  KEY idx_owner (owner, content_hash)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS co_manual_sheets (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY, import_id VARCHAR(40) NOT NULL, owner VARCHAR(190) NOT NULL, sheet_index INT NOT NULL, name VARCHAR(255) NOT NULL,
  data LONGTEXT NOT NULL, search_text MEDIUMTEXT NOT NULL, UNIQUE KEY idx_sheet (import_id, sheet_index), KEY idx_owner (owner)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
