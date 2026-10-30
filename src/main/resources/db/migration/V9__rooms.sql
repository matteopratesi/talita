-- The rooms of the house and who sleeps where (TALITA-11).
--
-- A room is the house, not a person: a name («Camera 3», «Stanza azzurra») and the beds, for one gathering. It holds no personal data, and it stays after the gathering
-- for the teachers to copy it the year after. Who sleeps in it is another table: a child (through its registration) or a teacher (through their account) in a room,
-- for the whole gathering. That is personal data, written and read only by the teachers, and it goes at the end of the gathering (`RoomsRule`, TALITA-7), with the
-- registration when it is withdrawn or deleted, and with the teacher when they are taken away.
--
-- Nothing else is in these tables, on purpose: no sex, no reason for a choice, no note, no free text about a person. The places are counted from the people who are in
-- the room and are not saved. Whether the gathering is over is checked by the application (it depends on today's date), in the same transaction as the change.
--
-- The composite keys make the database say what the application also checks: a child sleeps in a room of the gathering of its own registration, and so does a teacher.
-- A child is in one room only (the key of `room_child` is the registration), a teacher in one room per gathering (`room_teacher`): two teachers who put the same person
-- in two rooms at the same moment leave one row, the last (`insert … on conflict do update`).
--
-- The gathering is not deleted by the application, but the tests clear it with its registrations: the rooms go with it (`on delete cascade`).

create table room (
    id bigint generated always as identity primary key,
    gathering_id bigint not null references gathering (id) on delete cascade,
    name varchar(50) not null,
    beds smallint not null,
    constraint room_name_not_empty check (btrim(name) <> ''),
    constraint room_beds_range check (beds between 1 and 50),
    -- The composite key that the two tables below point to.
    constraint room_id_gathering unique (id, gathering_id)
);

-- A name once per gathering, whatever the capitals and the spaces at the sides.
create unique index room_name_in_gathering on room (gathering_id, lower(btrim(name)));

-- The composite key that `room_child` points to: only to say that the room and the registration are of the same gathering.
alter table registration add constraint registration_id_gathering unique (id, gathering_id);

create table room_child (
    registration_id bigint primary key,
    gathering_id bigint not null,
    room_id bigint not null,
    constraint room_child_registration foreign key (registration_id, gathering_id) references registration (id, gathering_id) on delete cascade,
    constraint room_child_room foreign key (room_id, gathering_id) references room (id, gathering_id) on delete cascade
);

create index room_child_room_id on room_child (room_id);

create table room_teacher (
    gathering_id bigint not null,
    teacher_account_id bigint not null references teacher (account_id) on delete cascade,
    room_id bigint not null,
    primary key (gathering_id, teacher_account_id),
    constraint room_teacher_room foreign key (room_id, gathering_id) references room (id, gathering_id) on delete cascade
);

create index room_teacher_room_id on room_teacher (room_id);

-- The register of the deletions has the rule of the rooms (V7): the people of a room are deleted at the end of the gathering.
alter table retention_log drop constraint retention_log_rule;
alter table retention_log add constraint retention_log_rule
    check (rule in ('notes', 'rooms', 'registrations', 'children', 'invitations', 'accounts', 'child-request', 'account-request', 'child-redo', 'account-redo'));
