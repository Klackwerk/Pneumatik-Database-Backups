alter table db
    add column backup_trigger varchar(255);

update db set backup_trigger = 'TRIGGER_DAILY' where backup_trigger is null;

alter table db
    modify backup_trigger varchar(255) not null;