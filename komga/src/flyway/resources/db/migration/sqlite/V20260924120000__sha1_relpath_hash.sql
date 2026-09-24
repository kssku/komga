-- CUSTOM FORK (feat/sha1-relpath-hash): migrate hashes from XXH3-128(content) to SHA-1(relative path).
--
-- Hasher now uses SHA-1 instead of XXH3-128, and BOOK.FILE_HASH is computed from the
-- book path relative to the library root's PARENT directory, so it is byte-identical
-- to LANraragi's compute_id for the same archive.
--
-- Existing hash values are therefore invalid. As with the upstream XXH3 migration
-- (V20230626150454__xxhash128.sql), we clear them rather than convert: the old values
-- cannot be transformed without re-reading every file, which is exactly the FUSE I/O
-- this fork exists to avoid.
--
-- What is cleared and what is not:
--   BOOK.FILE_HASH          cleared - recomputed as SHA1(relative path), zero I/O
--   MEDIA_PAGE.FILE_HASH    cleared - recomputed as SHA1(page bytes), sampled pages only
--   PAGE_HASH               cleared - values are content hashes, invalidated by the algorithm change
--   PAGE_HASH_THUMBNAIL     cleared - same
--   SYNC_POINT_BOOK.BOOK_FILE_HASH  NOT cleared - must stay in sync with Kobo devices
--
-- Cost: BOOK recompute is zero I/O. MEDIA_PAGE recompute is limited to the sampled pages
-- (KomgaProperties.pageHashing, default 3 first + 3 last per book), and happens lazily via
-- PageHashLifecycle. Books are picked up by the next scan / refresh.

delete from PAGE_HASH;
delete from PAGE_HASH_THUMBNAIL;
update BOOK set FILE_HASH = '';
update MEDIA_PAGE set FILE_HASH = '';