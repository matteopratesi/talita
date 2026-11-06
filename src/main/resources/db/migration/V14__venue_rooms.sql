-- The rooms belong to the venue (TALITA-20, part 2): the house has its rooms, and every gathering that is held there finds them.
--
-- The number is V14: V13 (the venues, part 1 of the same ticket) is the one before it, and TALITA-19 has no migration.
--
-- A room was a room of one gathering (`room.gathering_id`), copied every year. Now it is a room of a venue (`room.venue_id`, cascade: a venue that is taken away takes its rooms with it, and a venue is only taken
-- away when no gathering uses it), with a name that is unique in the venue whatever the capitals and the spaces at the sides, and with its beds. Who sleeps where stays with the gathering (`room_child`,
-- `room_teacher`: a child or a teacher in a room, for the whole gathering) and is still deleted at the end of it (`RoomsRule`): the rooms stay with the venue, without names, for the gathering of the year after.
-- Nothing in these tables says a person but the two tables of who sleeps where, and they have not changed in what they say.
--
-- The composite keys say what the application also checks. `room_child` and `room_teacher` have the venue too: the key to the room (`room_id`, `venue_id`) and the key to the gathering
-- (`gathering_id`, `venue_id`) carry the same venue, so the database refuses a person in a room of another venue than the one of their gathering. The key to the gathering has no cascade on an update of
-- the venue: a gathering cannot change venue while people are in the rooms of the one before (the application takes them out in the same transaction, after the teacher has confirmed). The key to the room cascades
-- on a delete, as before: a room that is taken away leaves its people without a room. Deleting the gathering still takes with it the people of its rooms (only the tests do it).
--
-- The data that is already there passes. The rooms of the most recent gathering of each venue (by the first day, then by the id), among the gatherings of that venue that have rooms, become the rooms of the venue,
-- with the same ids: who sleeps in them stays where they are. The other rooms are deleted, with who sleeps in them (already deleted when the gathering is over): the rooms of the gatherings before the most recent one
-- were copies of the same house, and those of a draft with no venue have no house to belong to.

-- The rooms to keep: the ones of the most recent gathering of each venue, among those that have rooms.
alter table room add column venue_id bigint;

update room
set venue_id = chosen.venue_id
from (
    select distinct on (gathering.venue_id) gathering.venue_id, gathering.id as gathering_id
    from gathering
    where gathering.venue_id is not null and exists (select 1 from room where room.gathering_id = gathering.id)
    order by gathering.venue_id, gathering.start_date desc, gathering.id desc
) as chosen
where room.gathering_id = chosen.gathering_id;

-- The others go, with the people in them (the keys of `room_child` and `room_teacher` still cascade from the room).
delete from room where venue_id is null;

-- The room of the venue: the keys that pointed to the gathering go, and the ones to the venue come.
alter table room_child drop constraint room_child_room;
alter table room_teacher drop constraint room_teacher_room;
drop index room_name_in_gathering;
-- Dropping the column drops with it the key to the gathering and the composite key `room_id_gathering`.
alter table room drop column gathering_id;
alter table room alter column venue_id set not null;
alter table room add constraint room_venue_fkey foreign key (venue_id) references venue (id) on delete cascade;
-- The composite key that the two tables of the people point to.
alter table room add constraint room_id_venue unique (id, venue_id);
-- A name once per venue, whatever the capitals and the spaces at the sides.
create unique index room_name_in_venue on room (venue_id, lower(btrim(name)));

-- The gathering and its venue: the key that the people point to.
alter table gathering add constraint gathering_id_venue unique (id, venue_id);

-- A child in a room: the room and the gathering of its registration are of the same venue.
alter table room_child add column venue_id bigint;
update room_child set venue_id = room.venue_id from room where room.id = room_child.room_id;
alter table room_child alter column venue_id set not null;
alter table room_child add constraint room_child_room foreign key (room_id, venue_id) references room (id, venue_id) on delete cascade;
alter table room_child add constraint room_child_gathering_venue foreign key (gathering_id, venue_id) references gathering (id, venue_id) on delete cascade;

-- A teacher in a room, the same.
alter table room_teacher add column venue_id bigint;
update room_teacher set venue_id = room.venue_id from room where room.id = room_teacher.room_id;
alter table room_teacher alter column venue_id set not null;
alter table room_teacher add constraint room_teacher_room foreign key (room_id, venue_id) references room (id, venue_id) on delete cascade;
alter table room_teacher add constraint room_teacher_gathering_venue foreign key (gathering_id, venue_id) references gathering (id, venue_id) on delete cascade;
