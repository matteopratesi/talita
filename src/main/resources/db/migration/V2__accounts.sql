-- Accounts and login links (TALITA-3, part B).
--
-- An account is an email address: it is born at the first successful login and holds nothing else.
-- Every account is a parent; being a teacher is a row in `teacher` (V3).

create table account (
    id    bigint generated always as identity primary key,
    -- Lowercase and without spaces at the sides: the application normalizes the address, and this
    -- constraint makes sure that nothing else can write it differently. 254 characters is the
    -- longest address that email allows.
    email varchar(254) not null unique,
    constraint account_email_normalized check (email = lower(btrim(email)) and email <> '')
);

-- The table of JdbcOneTimeTokenService (Spring Security), rewritten here: the script that comes
-- with the library uses `varchar_ignorecase`, which Postgres does not have, and a username of 50
-- characters, too short for some addresses. The column names are those that the service reads.
-- No foreign key to `account`: at the first login the account does not exist yet.
create table one_time_tokens (
    token_value varchar(36)  primary key,
    username    varchar(254) not null,
    expires_at  timestamptz  not null
);
