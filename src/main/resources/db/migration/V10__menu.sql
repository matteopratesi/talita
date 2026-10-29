-- The menu of a gathering (TALITA-12): one file for each gathering, kept in the database.
--
-- It is the file that whoever cooks prepares (a PDF, or the photo of the sheet), uploaded by a teacher and downloaded by the teachers: nothing of a person.
-- The application does not read it. It lives here and not on disk so that the image of the container and a dump of the database are still enough to
-- change machine, and so that the encrypted backups (TALITA-8) carry it. The number V10 is the first one that was free: V9 belongs to another ticket.
--
-- Deleting the gathering takes the menu with it (a gathering is never deleted by the application, the cascade is only the order of the tests).
create table gathering_menu (
    gathering_id bigint primary key references gathering (id) on delete cascade,
    -- The name that the browser sent, cleaned (MenuFile.cleanName): the last part, no control or direction characters, at most 100 characters and the
    -- extension of the kind that was recognised (.pdf, .jpg, .png), so 104 at most. It is only text, never an address.
    file_name text not null,
    -- The kind is recognised by the first bytes of the file, never by its name or by what the browser said.
    media_type text not null,
    content bytea not null,
    uploaded_at timestamptz not null,

    constraint gathering_menu_file_name_length check (char_length(file_name) between 1 and 104),
    constraint gathering_menu_file_name_plain check (file_name !~ '[[:cntrl:]]'),
    constraint gathering_menu_media_type check (media_type in ('application/pdf', 'image/jpeg', 'image/png')),
    -- 10 MB at most: the same limit as MenuFile.MAX_BYTES (a test checks that they agree).
    constraint gathering_menu_content_size check (octet_length(content) between 1 and 10485760)
);

-- A PDF and a JPEG are compressed already: Postgres would try to compress them again for nothing.
alter table gathering_menu alter column content set storage external;
