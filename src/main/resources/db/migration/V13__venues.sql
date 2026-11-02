-- The venues, the houses where a gathering is held (TALITA-20, part 1): a name and a place, kept for more than one gathering.
--
-- The number is V13: V12 (the devices and the passkeys, TALITA-15) is the last one on `main`, and TALITA-19 has no migration.
--
-- A venue is a house, not a person: nothing here is personal data. Both texts are public, like the place was: a gathering that has the venue shows its name and its place in the
-- page for the parents and in the preview of the link, and its place in the text of the custody that a parent accepts. The checks that keep an email, a phone number, an IBAN or the
-- first and last name of a child or of a teacher out of a public text are the ones of the application (`PublicText`, `PublicNames`): a table cannot know the names. The database holds the lengths and the
-- uniqueness: the name is unique whatever the capitals and the spaces at the sides, so that two teachers who save the same name at the same moment leave one row.
-- No contact person, no telephone, no address in fields, no map and no photo: the place is one line of text, as it was in the gathering.
--
-- `gathering.venue_id` takes the place of `gathering.place`. It has no cascade: a venue that a gathering uses is not deleted (the application says why, and the key says no to a request made by hand).
-- A draft may have no venue, as it could have no place; a gathering that is not a draft has one (the same rule as before, on the new column).
--
-- The data that is already there passes: every different place (without the capitals and the spaces at the sides) becomes a venue, with that place and, as its name, the place cut to 60 characters,
-- made unique if two places have the same first 60 characters (a number in brackets at the end). When the same place was written in different ways the text of the most recent gathering is the one kept. The gatherings
-- that had that place choose the venue; a draft with no place stays with no venue.

create table venue (
    id bigint generated always as identity primary key,
    name text not null,
    place text not null,
    constraint venue_name_length check (char_length(btrim(name)) between 1 and 60),
    constraint venue_place_length check (char_length(btrim(place)) between 1 and 200)
);

-- A name once, whatever the capitals and the spaces at the sides.
create unique index venue_name_unique on venue (lower(btrim(name)));

alter table gathering add column venue_id bigint references venue (id);

create index gathering_venue_id on gathering (venue_id);

-- The places that are there, one venue for each, the oldest first (the order of the first gathering that wrote them) so that a name made unique is always the later one.
do $$
declare
    wanted record;
    candidate text;
    attempt integer;
begin
    for wanted in
        select lower(btrim(place)) as key,
               (array_agg(btrim(place) order by start_date desc, id desc))[1] as place,
               min(id) as first_id
        from gathering
        where place is not null and btrim(place) <> ''
        group by lower(btrim(place))
        order by min(id)
    loop
        candidate := btrim(left(wanted.place, 60));
        attempt := 1;
        while exists (select 1 from venue where lower(btrim(name)) = lower(candidate)) loop
            attempt := attempt + 1;
            candidate := btrim(left(wanted.place, 60 - char_length(' (' || attempt || ')'))) || ' (' || attempt || ')';
        end loop;
        insert into venue (name, place) values (candidate, wanted.place);
    end loop;
end $$;

update gathering
set venue_id = venue.id
from venue
where gathering.place is not null and btrim(gathering.place) <> '' and lower(btrim(gathering.place)) = lower(btrim(venue.place));

alter table gathering drop constraint gathering_complete_when_not_draft;
-- Dropping the column drops `gathering_place_length` with it.
alter table gathering drop column place;
-- A draft may be incomplete; once the registrations open (or are closed) the venue and the full fee are there. The custodians are checked by the application: the rule depends on today's date.
alter table gathering add constraint gathering_complete_when_not_draft check (status = 'draft' or (venue_id is not null and full_fee_cents is not null));
