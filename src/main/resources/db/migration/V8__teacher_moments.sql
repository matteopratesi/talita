-- The moment a person was made a teacher, and the moment they were last invited (TALITA-7, review of the requests made again).
--
-- The job makes again, every night, the requests to delete an account that the register remembers (`account-redo`), and it must not wipe what the person did
-- **after** their request: an account that stayed only for the register of the readings can be invited again, or accept the invitation, without registering a child.
-- Only the moment tells those rows from the ones that the backup brought back, so the two tables get it. Nothing else: no name, no use of the account.
-- Inviting again renews the moment with the expiry (it is a new invitation). The rows that are there already take the moment of this migration, as the days of
-- `account.created_on` do in V7: no request made before it can be about them.
alter table teacher add column created_at timestamptz not null default now();
alter table teacher alter column created_at drop default;

alter table teacher_invitation add column invited_at timestamptz not null default now();
alter table teacher_invitation alter column invited_at drop default;
