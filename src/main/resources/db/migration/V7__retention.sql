-- How long the data are kept, and what is deleted when (TALITA-7).
--
-- Nothing here hides a row: a datum that has to go is deleted with `delete`, never marked as deleted. The rules by time, the job that
-- applies them and the deletions on request (a child, an account) are in the application (`talita.retention`); this script only gives them
-- the columns and the table they need, and relaxes the keys that pointed at an account without letting it go.

-- The day an account was born (the day of Rome, written by the application: no default), so that an account that stays empty can be deleted
-- 30 days later. Only the day, not the hour, and nothing about the use of the account (no last access). The accounts that are there already
-- take the day of this migration.
alter table account add column created_on date not null default ((now() at time zone 'Europe/Rome')::date);
alter table account alter column created_on drop default;

-- Who typed in a paper registration, who marked a presence and who registered a payment: when the account of that teacher is deleted the
-- row stays and says «a teacher who is not among the teachers any more». The other keys to `account` (`child`, `registration`, the
-- consent of the notes and the register of the readings) have no cascade on purpose: `ErasureService` settles them before the `delete`, so
-- that a mistake shows in the tests instead of silently detaching data.
alter table registration drop constraint registration_entered_by_account_id_fkey;
alter table registration add constraint registration_entered_by_account_id_fkey
    foreign key (entered_by_account_id) references account (id) on delete set null;

alter table checkin alter column recorded_by_account_id drop not null;
alter table checkin drop constraint checkin_recorded_by_account_id_fkey;
alter table checkin add constraint checkin_recorded_by_account_id_fkey
    foreign key (recorded_by_account_id) references account (id) on delete set null;

alter table payment alter column recorded_by_account_id drop not null;
alter table payment drop constraint payment_recorded_by_account_id_fkey;
alter table payment add constraint payment_recorded_by_account_id_fkey
    foreign key (recorded_by_account_id) references account (id) on delete set null;

-- A registration that keeps the acceptance but not the account (the parent deleted the account: the registration stays until its date, as the
-- proof of the custody). What tells a paper registration from an online one is no longer the account, which can go, but the acceptance: a paper
-- registration has none. The rest of the rules of V5 stay (whoever has an account has accepted: `registration_online_has_custody`).
alter table registration drop constraint registration_paper_has_no_custody;
alter table registration drop constraint registration_paper_has_a_teacher;
alter table registration drop constraint registration_paper_form_is_paper;
alter table registration add constraint registration_paper_has_no_consent check (custody_text is not null or not custody_religion_consent);
-- Only a paper registration is typed in by a teacher; it may lose the teacher (the account was deleted), so it is not required to have one.
alter table registration add constraint registration_online_not_typed_in check (custody_text is null or entered_by_account_id is null);
alter table registration add constraint registration_paper_form_is_paper check (custody_text is null or not paper_form_received);

-- The register of the deletions (art. 5.2 GDPR): when, which rule, how many rows. It has no personal data, except `target_id`: the number of
-- the child or of the account that was deleted on request, kept for 37 days (the rotation of the backups and a week) so that, if a backup older
-- than the request is restored, the request is made again. The rules by time write a row only when they have deleted something; a request
-- written again only when it has changed something. Everything a row says is a number: never a name, an address or a date of birth.
create table retention_log (
    id bigint generated always as identity primary key,
    done_at timestamptz not null,
    rule text not null,
    row_count integer not null,
    target_id bigint,
    constraint retention_log_rule check (rule in ('notes', 'registrations', 'children', 'invitations', 'accounts', 'child-request', 'account-request', 'child-redo', 'account-redo')),
    constraint retention_log_row_count check (row_count >= 0),
    -- Only a request has the number of what it deleted.
    constraint retention_log_target_only_on_request check (target_id is null or rule in ('child-request', 'account-request'))
);

create index retention_log_target on retention_log (target_id) where target_id is not null;
