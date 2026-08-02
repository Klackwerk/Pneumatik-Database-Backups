create table retention_policy (id bigint not null auto_increment, version bigint not null, database_id bigint not null, keep_count integer, keep_days integer, enabled bit not null, primary key (id)) engine=InnoDB;
alter table retention_policy add constraint UK_retention_policy_database unique (database_id);
alter table retention_policy add constraint FK_retention_policy_database foreign key (database_id) references db (id);
