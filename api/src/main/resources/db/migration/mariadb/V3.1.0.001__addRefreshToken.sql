-- Refresh tokens for the web session (see RefreshTokenService).
-- The token column holds a SHA-256 hash ("sha256:<hex>"), never a plaintext.
create table refresh_token (
    id           char(36)    not null,
    version      bigint      not null,
    token        varchar(96) not null,
    user_id      char(36)    not null,
    created_at   datetime(6) not null,
    expires_at   datetime(6) not null,
    last_used_at datetime(6) null,
    revoked_at   datetime(6) null,
    primary key (id)
) engine=InnoDB;

alter table refresh_token add constraint uk_refresh_token_token unique (token);
alter table refresh_token add constraint fk_refresh_token_user foreign key (user_id) references `user` (id);
create index idx_refresh_token_user on refresh_token (user_id);
