-- Replace all bigserial primary keys with UUIDs (varchar(36)).
-- Existing rows get fresh random UUIDs; foreign keys are remapped by joining
-- on the old numeric ids before those are dropped.
--
-- BREAKING for machine API clients: database ids in
-- POST /api/v1/backup/create/{databaseId} change — fetch the new ids from
-- GET /api/v1/databases after upgrading.

-- 1. new uuid values on every primary-key table
alter table "user" add column uuid_id varchar(36);
update "user" set uuid_id = gen_random_uuid()::text;
alter table role add column uuid_id varchar(36);
update role set uuid_id = gen_random_uuid()::text;
alter table host add column uuid_id varchar(36);
update host set uuid_id = gen_random_uuid()::text;
alter table db add column uuid_id varchar(36);
update db set uuid_id = gen_random_uuid()::text;
alter table backup add column uuid_id varchar(36);
update backup set uuid_id = gen_random_uuid()::text;
alter table api_key add column uuid_id varchar(36);
update api_key set uuid_id = gen_random_uuid()::text;
alter table retention_policy add column uuid_id varchar(36);
update retention_policy set uuid_id = gen_random_uuid()::text;

-- 2. remap foreign keys via the old numeric ids
alter table user_role add column uuid_user_id varchar(36);
alter table user_role add column uuid_role_id varchar(36);
update user_role ur set uuid_user_id = u.uuid_id from "user" u where ur.user_id = u.id;
update user_role ur set uuid_role_id = r.uuid_id from role r where ur.role_id = r.id;

alter table db add column uuid_host_id varchar(36);
update db d set uuid_host_id = h.uuid_id from host h where d.host_id = h.id;

alter table backup add column uuid_database_id varchar(36);
alter table backup add column uuid_created_by_id varchar(36);
update backup b set uuid_database_id = d.uuid_id from db d where b.database_id = d.id;
update backup b set uuid_created_by_id = u.uuid_id from "user" u where b.created_by_id = u.id;

alter table api_key add column uuid_created_by_id varchar(36);
update api_key a set uuid_created_by_id = u.uuid_id from "user" u where a.created_by_id = u.id;

alter table retention_policy add column uuid_database_id varchar(36);
update retention_policy rp set uuid_database_id = d.uuid_id from db d where rp.database_id = d.id;

-- 3. drop the old constraints (default names from the 2.0.0 baseline)
alter table backup drop constraint backup_database_id_fkey;
alter table backup drop constraint backup_created_by_id_fkey;
alter table db drop constraint db_host_id_fkey;
alter table user_role drop constraint user_role_user_id_fkey;
alter table user_role drop constraint user_role_role_id_fkey;
alter table api_key drop constraint api_key_created_by_id_fkey;
alter table retention_policy drop constraint retention_policy_database_id_fkey;
alter table retention_policy drop constraint uk_retention_policy_database;

-- 4. swap the key columns
alter table user_role drop constraint user_role_pkey;
alter table user_role drop column user_id;
alter table user_role drop column role_id;
alter table user_role rename column uuid_user_id to user_id;
alter table user_role rename column uuid_role_id to role_id;
alter table user_role alter column user_id set not null;
alter table user_role alter column role_id set not null;
alter table user_role add primary key (user_id, role_id);

alter table "user" drop constraint user_pkey;
alter table "user" drop column id;
alter table "user" rename column uuid_id to id;
alter table "user" alter column id set not null;
alter table "user" add primary key (id);

alter table role drop constraint role_pkey;
alter table role drop column id;
alter table role rename column uuid_id to id;
alter table role alter column id set not null;
alter table role add primary key (id);

alter table host drop constraint host_pkey;
alter table host drop column id;
alter table host rename column uuid_id to id;
alter table host alter column id set not null;
alter table host add primary key (id);

alter table db drop constraint db_pkey;
alter table db drop column id;
alter table db rename column uuid_id to id;
alter table db alter column id set not null;
alter table db add primary key (id);
alter table db drop column host_id;
alter table db rename column uuid_host_id to host_id;
alter table db alter column host_id set not null;

alter table backup drop constraint backup_pkey;
alter table backup drop column id;
alter table backup rename column uuid_id to id;
alter table backup alter column id set not null;
alter table backup add primary key (id);
alter table backup drop column database_id;
alter table backup rename column uuid_database_id to database_id;
alter table backup alter column database_id set not null;
alter table backup drop column created_by_id;
alter table backup rename column uuid_created_by_id to created_by_id;

alter table api_key drop constraint api_key_pkey;
alter table api_key drop column id;
alter table api_key rename column uuid_id to id;
alter table api_key alter column id set not null;
alter table api_key add primary key (id);
alter table api_key drop column created_by_id;
alter table api_key rename column uuid_created_by_id to created_by_id;
alter table api_key alter column created_by_id set not null;

alter table retention_policy drop constraint retention_policy_pkey;
alter table retention_policy drop column id;
alter table retention_policy rename column uuid_id to id;
alter table retention_policy alter column id set not null;
alter table retention_policy add primary key (id);
alter table retention_policy drop column database_id;
alter table retention_policy rename column uuid_database_id to database_id;
alter table retention_policy alter column database_id set not null;

-- 5. re-create the foreign keys with stable names
alter table db add constraint fk_db_host foreign key (host_id) references host (id);
alter table backup add constraint fk_backup_database foreign key (database_id) references db (id);
alter table backup add constraint fk_backup_created_by foreign key (created_by_id) references "user" (id);
alter table user_role add constraint fk_user_role_user foreign key (user_id) references "user" (id);
alter table user_role add constraint fk_user_role_role foreign key (role_id) references role (id);
alter table api_key add constraint fk_api_key_created_by foreign key (created_by_id) references "user" (id);
alter table retention_policy add constraint fk_retention_policy_database foreign key (database_id) references db (id);
alter table retention_policy add constraint uk_retention_policy_database unique (database_id);
