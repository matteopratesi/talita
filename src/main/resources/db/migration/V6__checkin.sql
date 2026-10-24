-- The check-in of the gathering: the presence of each day and the payments (TALITA-6).
--
-- A presence is a registration on a day of the gathering, with the moment it was marked and the teacher who marked it: one for each pair,
-- whatever the teachers do (two teachers who mark the same child at the same moment leave one row). A payment is an amount that a teacher
-- received for a registration, with the moment and the teacher: the amount paid is the sum of the payments, the balance is worked out from
-- it and from the fee that is due (`Quota`), and none of them is saved. A payment that is wrong is deleted: it goes with all its data.
--
-- Nothing here changes when the fees or the days of a registration change (R2 of TALITA-4): only what is due does. Whether the day is one of
-- the gathering and not after today is checked by the application, because it depends on the dates, which change, and on the clock.
--
-- Both tables go away with their registration (`on delete cascade`: TALITA-7 deletes registrations after a year, and these with them). The
-- reference to the teacher has no cascade, as in V5: what happens to the accounts is for TALITA-7.

create table checkin (
    registration_id bigint not null references registration (id) on delete cascade,
    day date not null,
    recorded_at timestamptz not null,
    recorded_by_account_id bigint not null references account (id),
    primary key (registration_id, day)
);

create table payment (
    id bigint generated always as identity primary key,
    registration_id bigint not null references registration (id) on delete cascade,
    -- From one cent to 1,000 euros: the same limit as the fees.
    amount_cents integer not null check (amount_cents between 1 and 100000),
    recorded_at timestamptz not null,
    recorded_by_account_id bigint not null references account (id),
    -- Written by the server when it draws the form, and unique in the database: the same form sent twice (a double tap, a network that comes
    -- back) makes one payment.
    request_id uuid not null unique
);

create index payment_registration on payment (registration_id);
