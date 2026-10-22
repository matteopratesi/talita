-- Teachers and invitations (TALITA-3, parts C and D).
--
-- Every account is a parent; a teacher is an account with a row here. The name is written by the
-- teacher when they accept the invitation (the first teacher too) and corrected from the profile: the
-- invitation has only the address, the data comes from the person. It is deleted with the row when the
-- teacher is removed; the account stays.

create table teacher (
    account_id bigint primary key references account (id) on delete cascade,
    first_name varchar(100) not null check (btrim(first_name) <> ''),
    last_name  varchar(100) not null check (btrim(last_name) <> '')
);

-- Someone who has been invited to be a teacher and has not accepted yet. One per address: inviting again
-- renews the expiry. Without a foreign key to `account`: whoever is invited may never have entered.
create table teacher_invitation (
    email      varchar(254) primary key,
    expires_at timestamptz  not null,
    constraint teacher_invitation_email_normalized check (email = lower(btrim(email)) and email <> '')
);
