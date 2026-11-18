-- The token for the APIs of configuration (TALITA-21) and the register of the requests made with it.
--
-- `api_token` is the token of a teacher: the account (one token per teacher, so `account_id` is unique and a new token takes the place of the old one), the SHA-256 of the token (32 bytes: the token
-- itself is shown once and never written, so whoever reads the database cannot use it) and the day it was made (a day of Rome, from the one clock of the application). It does not say when it
-- expires: the expiry is the day of creation plus 30 days (`RetentionPolicy`), and the job deletes the row that night. No label written by hand (it would be a free note), no last use, no address of
-- the network and no browser. The key goes to `teacher` with a cascade: a teacher who is taken away from the teachers, or whose account is deleted, loses the token in the same moment.
--
-- `api_request` is the register of the requests that a valid token made: the moment, the method, the route as a model (`/api/strutture/{id}/camere`, never the address that was asked for) and the code
-- of the answer. **Nothing that is personal**: it does not say which token or which teacher, and it holds no body, no address of the network and no browser. It serves to see a use of the token that
-- the teacher does not recognize. The job deletes the rows after 30 days.
--
-- The number is V16: V15 (the forms of the venues, TALITA-20) is the last one on `main`.

create table api_token (
    id         bigint generated always as identity primary key,
    account_id bigint not null references teacher (account_id) on delete cascade,
    -- SHA-256 of the token: `talita_` and 43 characters of base64url.
    token_hash bytea  not null,
    created_on date   not null,
    constraint api_token_account_unique unique (account_id),
    constraint api_token_hash_unique unique (token_hash),
    constraint api_token_hash_size check (octet_length(token_hash) = 32)
);

create table api_request (
    id     bigint generated always as identity primary key,
    at     timestamptz  not null,
    method varchar(6)   not null,
    route  varchar(100) not null,
    status integer      not null,
    constraint api_request_method check (method in ('GET', 'POST', 'PUT', 'DELETE')),
    constraint api_request_status check (status between 100 and 599)
);

create index api_request_at on api_request (at);
