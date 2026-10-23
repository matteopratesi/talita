-- The gathering and its custodians (TALITA-4).
--
-- A gathering is an event, not a person: nothing here is personal data. The place is a house for retreats
-- and says where and when there will be children, which is why the public page has a code that nobody can
-- guess (`public_code`) and a finished gathering stops saying where it was. The custodians are teachers
-- (`teacher`, TALITA-3): the name that a teacher wrote for the text of the custody is read from there and
-- copied nowhere.

create type gathering_status as enum ('draft', 'open', 'closed');

create table gathering (
    id bigint generated always as identity primary key,
    -- 16 characters of base64url, from at least 96 bits of SecureRandom (made by the application).
    public_code text not null unique,
    title text not null,
    start_date date not null,
    end_date date not null,
    place text,
    -- Fees in cents. Null: not provided. Zero: free of charge.
    full_fee_cents integer,
    daily_fee_cents integer,
    lunch_only_fee_cents integer,
    info_text text,
    what_to_bring text,
    status gathering_status not null default 'draft',

    constraint gathering_public_code_format check (public_code ~ '^[A-Za-z0-9_-]{16}$'),
    constraint gathering_title_length check (char_length(btrim(title)) between 1 and 100),
    constraint gathering_dates_order check (end_date >= start_date),
    -- Seven days at most: the days of presence are all those from the first to the last.
    constraint gathering_duration check (end_date - start_date <= 6),
    constraint gathering_place_length check (place is null or char_length(place) between 1 and 200),
    constraint gathering_full_fee_range check (full_fee_cents is null or full_fee_cents between 0 and 100000),
    constraint gathering_daily_fee_range check (daily_fee_cents is null or daily_fee_cents between 0 and 100000),
    constraint gathering_lunch_only_fee_range check (lunch_only_fee_cents is null or lunch_only_fee_cents between 0 and 100000),
    constraint gathering_info_text_length check (info_text is null or char_length(info_text) <= 5000),
    constraint gathering_what_to_bring_length check (what_to_bring is null or char_length(what_to_bring) <= 2000),
    -- A draft may be incomplete; once the registrations open (or are closed) the place and the full fee are there.
    -- The custodians are checked by the application: the rule depends on today's date.
    constraint gathering_complete_when_not_draft check (status = 'draft' or (place is not null and full_fee_cents is not null))
);

-- At most one gathering that is not closed: a draft or an open one. The constant expression makes every such
-- row collide with the others.
create unique index gathering_only_one_not_closed on gathering ((true)) where status <> 'closed';

-- The teachers who take the children in custody for this gathering. Both foreign keys cascade: deleting a
-- teacher only reaches the gatherings that are over, because «Togli» (TALITA-3, extended by TALITA-4) stops
-- before for the others, and whether a gathering is over depends on today, which a constraint cannot know.
create table gathering_custodian (
    gathering_id bigint not null references gathering (id) on delete cascade,
    teacher_account_id bigint not null references teacher (account_id) on delete cascade,
    primary key (gathering_id, teacher_account_id)
);

create index gathering_custodian_teacher on gathering_custodian (teacher_account_id);
