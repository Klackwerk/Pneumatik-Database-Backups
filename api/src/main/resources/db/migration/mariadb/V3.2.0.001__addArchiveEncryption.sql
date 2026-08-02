-- Marks archives written with AES-256-GCM. Null means plaintext, so backups
-- taken before encryption was switched on stay readable exactly as they are.
alter table backup add column encrypted bit default null;
