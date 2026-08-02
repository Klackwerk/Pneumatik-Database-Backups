-- SSH host key verification (see Host.verifyHostKey / BackupService).
-- Defaults to off so existing hosts keep connecting after the upgrade; the
-- Hosts dialog turns it on per host.
alter table host add column verify_host_key bit not null default 0;
alter table host add column host_key longtext null;

-- API keys can be limited to specific databases. No rows for a key means
-- "every database", which is what every key created before this could do.
create table api_key_database (
    api_key_id  char(36) not null,
    database_id char(36) not null,
    primary key (api_key_id, database_id)
) engine=InnoDB;

alter table api_key_database add constraint fk_api_key_database_api_key
    foreign key (api_key_id) references api_key (id);
alter table api_key_database add constraint fk_api_key_database_database
    foreign key (database_id) references db (id);
