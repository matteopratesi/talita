-- btree_gist: EXCLUDE constraints that mix equality with daterange overlap (memberships).
-- pg_trgm, unaccent: name search and deduplication that tolerate spelling variants.
create extension if not exists btree_gist;
create extension if not exists pg_trgm;
create extension if not exists unaccent;
