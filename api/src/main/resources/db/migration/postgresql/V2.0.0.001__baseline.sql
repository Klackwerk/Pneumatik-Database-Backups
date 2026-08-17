-- Full schema baseline for fresh PostgreSQL installs (2.0.0).
-- MariaDB installs instead follow the incremental history under db/migration/mariadb.

create table "user" (
    id bigserial primary key,
    version bigint not null,
    username varchar(255) not null,
    "password" varchar(255) not null,
    email varchar(255) not null,
    enabled boolean not null,
    account_expired boolean not null,
    account_locked boolean not null,
    password_expired boolean not null
);
alter table "user" add constraint uk_user_username unique (username);
alter table "user" add constraint uk_user_email unique (email);

create table role (
    id bigserial primary key,
    version bigint not null,
    authority varchar(255) not null
);
alter table role add constraint uk_role_authority unique (authority);

create table user_role (
    user_id bigint not null references "user" (id),
    role_id bigint not null references role (id),
    primary key (user_id, role_id)
);

create table host (
    id bigserial primary key,
    version bigint not null,
    friendly_name varchar(255),
    hostname varchar(255) not null,
    port integer not null,
    ssh_hostname varchar(255),
    ssh_user varchar(255),
    ssh_port integer,
    ssh_key text,
    usessl boolean not null default false
);
alter table host add constraint uk_host_friendly_name unique (friendly_name);

create table db (
    id bigserial primary key,
    version bigint not null,
    friendly_name varchar(255),
    database_name varchar(255) not null,
    host_id bigint not null references host (id),
    "user" varchar(255),
    "password" varchar(255),
    storage_provider varchar(255) not null,
    backup_trigger varchar(255) not null,
    database_type varchar(255)
);

create table backup (
    id bigserial primary key,
    version bigint not null,
    database_id bigint not null references db (id),
    created_at timestamp,
    executed_at timestamp,
    filename varchar(255),
    full_path varchar(255),
    size varchar(255),
    state varchar(255),
    exit_code integer,
    success boolean not null,
    created_by_id bigint references "user" (id),
    storage_provider varchar(255) not null
);

create table api_key (
    id bigserial primary key,
    version bigint not null,
    "key" varchar(255) not null,
    key_hint varchar(16),
    created_at timestamp not null,
    created_by_id bigint not null references "user" (id),
    valid_until timestamp,
    comment varchar(255),
    last_connected_at timestamp
);

create table retention_policy (
    id bigserial primary key,
    version bigint not null,
    database_id bigint not null references db (id),
    keep_count integer,
    keep_days integer,
    enabled boolean not null
);
alter table retention_policy add constraint uk_retention_policy_database unique (database_id);
