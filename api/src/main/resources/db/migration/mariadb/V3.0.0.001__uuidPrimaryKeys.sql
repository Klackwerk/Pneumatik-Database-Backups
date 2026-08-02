-- Replace all bigint auto-increment primary keys with UUIDs (char(36)).
-- Existing rows get fresh random UUIDs; foreign keys are remapped by joining
-- on the old numeric ids before those are dropped.
--
-- BREAKING for machine API clients: database ids in
-- POST /api/v1/backup/create/{databaseId} change — fetch the new ids from
-- GET /api/v1/databases after upgrading.

-- 1. new uuid values on every primary-key table
alter table user add column uuid_id char(36) null;
update user set uuid_id = uuid();
alter table role add column uuid_id char(36) null;
update role set uuid_id = uuid();
alter table host add column uuid_id char(36) null;
update host set uuid_id = uuid();
alter table db add column uuid_id char(36) null;
update db set uuid_id = uuid();
alter table backup add column uuid_id char(36) null;
update backup set uuid_id = uuid();
alter table api_key add column uuid_id char(36) null;
update api_key set uuid_id = uuid();
alter table retention_policy add column uuid_id char(36) null;
update retention_policy set uuid_id = uuid();

-- 2. remap foreign keys via the old numeric ids
alter table user_role add column uuid_user_id char(36) null;
alter table user_role add column uuid_role_id char(36) null;
update user_role ur join user u on ur.user_id = u.id set ur.uuid_user_id = u.uuid_id;
update user_role ur join role r on ur.role_id = r.id set ur.uuid_role_id = r.uuid_id;

alter table db add column uuid_host_id char(36) null;
update db d join host h on d.host_id = h.id set d.uuid_host_id = h.uuid_id;

alter table backup add column uuid_database_id char(36) null;
alter table backup add column uuid_created_by_id char(36) null;
update backup b join db d on b.database_id = d.id set b.uuid_database_id = d.uuid_id;
update backup b join user u on b.created_by_id = u.id set b.uuid_created_by_id = u.uuid_id;

alter table api_key add column uuid_created_by_id char(36) null;
update api_key a join user u on a.created_by_id = u.id set a.uuid_created_by_id = u.uuid_id;

alter table retention_policy add column uuid_database_id char(36) null;
update retention_policy rp join db d on rp.database_id = d.id set rp.uuid_database_id = d.uuid_id;

-- 3. drop the old constraints (names from the migration history)
alter table backup drop foreign key FK7swagyfna4r943h6tuyyyed3;
alter table backup drop foreign key FK4jn4kco1xy3v5ip0pspykh9p2;
alter table db drop foreign key FKrhkalet2o5h2p4oqj26r1yt59;
alter table user_role drop foreign key FK859n2jvi8ivhui0rl0esws6o;
alter table user_role drop foreign key FKa68196081fvovjhkek5m97n3y;
alter table api_key drop foreign key FKhxu12faponue7mmt3e4aj9g3f;
alter table retention_policy drop foreign key FK_retention_policy_database;
alter table retention_policy drop index UK_retention_policy_database;

-- 4. swap the key columns
alter table user_role drop primary key;
alter table user_role drop column user_id;
alter table user_role drop column role_id;
alter table user_role change uuid_user_id user_id char(36) not null;
alter table user_role change uuid_role_id role_id char(36) not null;
alter table user_role add primary key (user_id, role_id);

alter table user modify id bigint not null;
alter table user drop primary key;
alter table user drop column id;
alter table user change uuid_id id char(36) not null;
alter table user add primary key (id);

alter table role modify id bigint not null;
alter table role drop primary key;
alter table role drop column id;
alter table role change uuid_id id char(36) not null;
alter table role add primary key (id);

alter table host modify id bigint not null;
alter table host drop primary key;
alter table host drop column id;
alter table host change uuid_id id char(36) not null;
alter table host add primary key (id);

alter table db modify id bigint not null;
alter table db drop primary key;
alter table db drop column id;
alter table db change uuid_id id char(36) not null;
alter table db add primary key (id);
alter table db drop column host_id;
alter table db change uuid_host_id host_id char(36) not null;

alter table backup modify id bigint not null;
alter table backup drop primary key;
alter table backup drop column id;
alter table backup change uuid_id id char(36) not null;
alter table backup add primary key (id);
alter table backup drop column database_id;
alter table backup change uuid_database_id database_id char(36) not null;
alter table backup drop column created_by_id;
alter table backup change uuid_created_by_id created_by_id char(36) null;

alter table api_key modify id bigint not null;
alter table api_key drop primary key;
alter table api_key drop column id;
alter table api_key change uuid_id id char(36) not null;
alter table api_key add primary key (id);
alter table api_key drop column created_by_id;
alter table api_key change uuid_created_by_id created_by_id char(36) not null;

alter table retention_policy modify id bigint not null;
alter table retention_policy drop primary key;
alter table retention_policy drop column id;
alter table retention_policy change uuid_id id char(36) not null;
alter table retention_policy add primary key (id);
alter table retention_policy drop column database_id;
alter table retention_policy change uuid_database_id database_id char(36) not null;

-- 5. re-create the foreign keys with stable names
alter table db add constraint fk_db_host foreign key (host_id) references host (id);
alter table backup add constraint fk_backup_database foreign key (database_id) references db (id);
alter table backup add constraint fk_backup_created_by foreign key (created_by_id) references user (id);
alter table user_role add constraint fk_user_role_user foreign key (user_id) references user (id);
alter table user_role add constraint fk_user_role_role foreign key (role_id) references role (id);
alter table api_key add constraint fk_api_key_created_by foreign key (created_by_id) references user (id);
alter table retention_policy add constraint fk_retention_policy_database foreign key (database_id) references db (id);
alter table retention_policy add constraint uk_retention_policy_database unique (database_id);
