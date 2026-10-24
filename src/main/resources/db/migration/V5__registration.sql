-- Children, registrations and the notes on health (TALITA-5).
--
-- A registration is a child at a gathering, and it is also the custody: the text that the parent accepted is kept whole, with the
-- version of its model, the moment and the address of whoever accepted. The fee that is due is never saved: it is worked out from the
-- fees of the gathering and the days (see `Quota`). The notes on health (allergies, intolerances, medicines) are in a table of their own,
-- only with the consent of the parent, and every reading by a teacher leaves a row in `health_note_access` (no content).
--
-- Everything that points to `child` and `account` has no cascade: what is deleted, and when, is for TALITA-7. The children of a paper
-- registration (no account) go away with their registration, there too.

create type registration_attendance as enum ('all_days', 'days');
create type registration_status as enum ('confirmed', 'maybe', 'withdrawn');
create type registration_day_kind as enum ('full', 'lunch');

-- A child. It belongs to the account that registered it and stays for the year after; a child of a paper registration has no account.
create table child (
    id bigint generated always as identity primary key,
    account_id bigint references account (id),
    first_name varchar(100) not null,
    last_name varchar(100) not null,
    birth_date date not null,
    constraint child_first_name_not_empty check (btrim(first_name) <> ''),
    constraint child_last_name_not_empty check (btrim(last_name) <> '')
);

create index child_account on child (account_id);

create table registration (
    id bigint generated always as identity primary key,
    gathering_id bigint not null references gathering (id),
    child_id bigint not null references child (id),
    -- The account that registered the child online. Null: a paper registration, typed in by a teacher (`entered_by_account_id`).
    account_id bigint references account (id),
    -- One for the registration, the same for the registrations of one submission and for the gathering of that parent.
    phone varchar(20) not null,
    attendance registration_attendance not null,
    status registration_status not null,
    -- The custody that was accepted: the exact text shown, the version of the model, when, and the address of whoever accepted.
    -- All empty (a paper registration) or all there.
    custody_text text,
    custody_text_version varchar(30),
    custody_accepted_at timestamptz,
    custody_accepted_by_email varchar(254),
    -- The box about the beliefs that can be guessed from taking part (docs/03): kept with the acceptance.
    custody_religion_consent boolean not null default false,
    paper_form_received boolean not null default false,
    entered_by_account_id bigint references account (id),
    created_at timestamptz not null,
    updated_at timestamptz not null,

    constraint registration_one_per_child unique (gathering_id, child_id),
    -- The composite key that registration_day points to: a day is only for a registration of some days.
    constraint registration_id_attendance unique (id, attendance),
    constraint registration_phone_length check (char_length(phone) between 6 and 20),
    constraint registration_custody_whole check (
        (custody_text is null) = (custody_text_version is null)
        and (custody_text is null) = (custody_accepted_at is null)
        and (custody_text is null) = (custody_accepted_by_email is null)
    ),
    constraint registration_custody_not_empty check (custody_text is null or btrim(custody_text) <> ''),
    -- Whoever registers online has accepted; a paper registration has no acceptance, no beliefs box and a teacher who typed it in.
    constraint registration_online_has_custody check (account_id is null or custody_text is not null),
    constraint registration_paper_has_no_custody check (account_id is not null or (custody_text is null and not custody_religion_consent)),
    constraint registration_paper_has_a_teacher check ((account_id is null) = (entered_by_account_id is not null)),
    constraint registration_paper_form_is_paper check (account_id is null or not paper_form_received)
);

create index registration_gathering on registration (gathering_id, created_at);
create index registration_account on registration (account_id);

-- The days of a registration «some days»: the gathering days that the child comes, as a whole day or for lunch only.
create table registration_day (
    registration_id bigint not null,
    attendance registration_attendance not null default 'days',
    day date not null,
    kind registration_day_kind not null,
    primary key (registration_id, day),
    constraint registration_day_only_some_days check (attendance = 'days'),
    constraint registration_day_registration foreign key (registration_id, attendance) references registration (id, attendance) on delete cascade
);

-- Allergies, intolerances, medicines: with the explicit consent of the parent (online, or on the paper form). Deleted at once when the
-- registration is withdrawn and when the parent takes them away. Only `HealthNotes` reads and writes this table.
create table health_note (
    registration_id bigint primary key references registration (id) on delete cascade,
    allergies text,
    intolerances text,
    medications text,
    consent_at timestamptz not null,
    consent_by_account_id bigint references account (id),
    consent_on_paper boolean not null default false,
    constraint health_note_allergies_length check (allergies is null or char_length(allergies) between 1 and 1000),
    constraint health_note_intolerances_length check (intolerances is null or char_length(intolerances) between 1 and 1000),
    constraint health_note_medications_length check (medications is null or char_length(medications) between 1 and 1000),
    constraint health_note_not_empty check (allergies is not null or intolerances is not null or medications is not null),
    constraint health_note_consent_by_one check ((consent_by_account_id is not null) <> consent_on_paper)
);

-- Who read the notes and when: a teacher, a registration, a moment. Never the text, and not whether there was any.
create table health_note_access (
    id bigint generated always as identity primary key,
    registration_id bigint not null references registration (id) on delete cascade,
    teacher_account_id bigint not null references account (id),
    accessed_at timestamptz not null
);

create index health_note_access_registration on health_note_access (registration_id, accessed_at desc);
