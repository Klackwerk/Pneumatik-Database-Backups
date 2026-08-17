-- Per-backup run details: command output (for failure diagnosis), finish
-- timestamp (duration = finished_at - executed_at) and exact byte sizes of
-- the raw dump vs the stored archive.
alter table backup add column finished_at timestamp null;
alter table backup add column output text null;
alter table backup add column raw_size_bytes bigint null;
alter table backup add column archived_size_bytes bigint null;
