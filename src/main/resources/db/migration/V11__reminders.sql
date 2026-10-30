-- Reminders by email (TALITA-13): «in forse» for who has a registration that is not confirmed, «avviso» for who asked to be told when a new gathering opens.
--
-- The number is V11 and not the first one that was free: V9 (the rooms, TALITA-11) and V10 (the menu, TALITA-12) belong to the tickets that are developed in
-- parallel; Flyway accepts the gap, and when the three tickets are on `main` the numbers are V9, V10 and V11.
--
-- Three columns of `account` hold what a person said about the mail that Talità sends them besides the login links (which always arrive):
--   - `notice_consent_at`: the moment they asked for the «avviso» (the consent, art. 7.1: to be able to show it). Without it the «avviso» does not leave.
--   - `reminders_off_at`: the moment they asked to receive no reminders at all (from the mail, with a tap, or from the profile). It holds for both kinds.
--   - `reminder_code`: 16 characters of base64url from 96 random bits, the same shape as `gathering.public_code`. It is only in the link «Non mandarmi più promemoria»
--     of the mail (in the query of the address) and in the body of the POST of the page it leads to; it is written when the first reminder is made for the account and
--     it is never in the log. It goes with the account when the account is deleted (TALITA-7), like the two moments.
-- A person who stops loses the consent at the same time, and cannot ask for it again without taking the reminders back: the two columns are never both set.
--
-- `reminder_batch` is the register of what was sent, without anything personal: which gathering, which kind, when, to how many people, how many mails did not leave.
-- It has no recipients and no teacher (who received what, and who tapped the button, is not kept). It is what keeps a reminder from being sent twice within 7 days
-- and what says to the teachers when the last one left. A row is written only if at least one mail left (`recipients >= 1`: the application never writes a batch of
-- which nothing left, so that the teacher can try again at once).
--
-- A gathering is never deleted by the application: the cascade is only the order of the tests, which clear the gatherings with what hangs from them.

create type reminder_kind as enum ('maybe', 'notice');

alter table account add column notice_consent_at timestamptz;
alter table account add column reminders_off_at timestamptz;
alter table account add column reminder_code text;

alter table account add constraint account_reminder_code_unique unique (reminder_code);
alter table account add constraint account_reminder_code_format check (reminder_code is null or reminder_code ~ '^[A-Za-z0-9_-]{16}$');
alter table account add constraint account_notice_or_stop check (notice_consent_at is null or reminders_off_at is null);

create table reminder_batch (
    id bigint generated always as identity primary key,
    gathering_id bigint not null references gathering (id) on delete cascade,
    kind reminder_kind not null,
    sent_at timestamptz not null,
    recipients smallint not null,
    failed smallint not null,
    constraint reminder_batch_recipients check (recipients >= 1),
    constraint reminder_batch_failed check (failed between 0 and recipients)
);

-- «The last batch of this kind for this gathering»: the one that says when the next can leave.
create index reminder_batch_last on reminder_batch (gathering_id, kind, sent_at desc);
