-- The forms of a venue (TALITA-20, part 3): the documents that a house asks for, which Talità prints from Arrivi (for example a register of the children who are there, for the tourist tax).
--
-- A form has a name and four columns to choose, and nothing else: it is **not a document and holds no value**. The database keeps which columns the sheet has, never what goes in them:
--
-- - `with_age`: the age in years of each child on the first day of the gathering (never the date of birth);
-- - `with_days`: a column for each day, «presente», «solo pranzo» or empty;
-- - `with_teacher_document` and `with_teacher_tax_code`: a field for each teacher in the page of the print. What a teacher writes there stays in the browser and goes only on the paper: Talità does not
--   receive it and does not keep it, so these two columns say only that the sheet has the field.
--
-- A form belongs to a venue, and goes with it (cascade): a venue that no gathering uses is taken away with its rooms and its forms. The name is unique in the venue whatever the capitals and the spaces at the
-- sides, so two teachers who write the same name at the same moment leave one form (the index says no to the second). Nothing in this table is personal data, and the four columns are off at the start: every column
-- is a piece of data that leaves the application or is written on the sheet, and who writes the form chooses it.
create table venue_form (
    id bigint generated always as identity primary key,
    venue_id bigint not null references venue (id) on delete cascade,
    name text not null,
    with_age boolean not null default false,
    with_days boolean not null default false,
    with_teacher_document boolean not null default false,
    with_teacher_tax_code boolean not null default false,
    constraint venue_form_name_length check (char_length(btrim(name)) between 1 and 60)
);

create unique index venue_form_name_in_venue on venue_form (venue_id, lower(btrim(name)));
