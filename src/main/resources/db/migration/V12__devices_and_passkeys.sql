-- «Ricordami su questo telefono» and passkeys (TALITA-15): the devices that enter without a link, the passkeys, and what lets an account close all its sessions at once.
--
-- The number is V12: V9 (the rooms, TALITA-11), V10 (the menu, TALITA-12) and V11 (the reminders, TALITA-13) are on `main`.
--
-- Two columns of `account`:
--   - `session_generation`: a number that only grows. The session remembers the number it was born with; when «Esci da tutti gli altri dispositivi» raises it, or an account that
--     stays for the register of the readings is asked to be deleted, every other session ends at its next request (TALITA-7 left that case open). It says how many times it happened and
--     nothing else: no moment, no device.
--   - `passkey_handle`: the identifier that the phone sends back with a passkey to say which account it is of (the «user handle» of WebAuthn). 64 random bytes, one per account, born when
--     the first passkey is added. It says nothing of the person: it is not the id and not the address. It is emptied when a request to delete the account leaves the account for the readings.
--
-- `remembered_device` is the row of a browser that touched «Ricordami su questo telefono»: the account, a random `selector` (128 bits, in the cookie and in the row, it says which row) and the
-- SHA-256 of the random value of the cookie (256 bits: the value is never written here), and the day it was made (a day of Rome, from the one clock of the application). It does not say
-- when it expires: the expiry is the day of creation plus the length of the role of the person today (30 days for a parent, 7 for a teacher, `RetentionPolicy`), and the job deletes the
-- row that night. No last use that is kept, no name of the device, no browser, no address of the network.
-- The value of the cookie changes at every re-entry, and for 60 seconds after a change the value before it is good too (two tabs that the phone reopens together present the same cookie, and
-- the second must not be taken for a thief): `previous_hash` is the SHA-256 of that value (never the value) and `rotated_at` the moment of the change, from the one clock of the application, which
-- is the moment of the last re-entry with the cookie. They are empty until the first re-entry, they are only for the margin, and the job of the retention empties both the night after (the rows
-- whose 60 seconds are over): they are not a last use that stays.
--
-- `passkey` is a passkey of an account: the identifier of the credential (the phone chooses it), the public key (the private one never leaves the phone), the counter of the signatures, the
-- flags that the phone gave at the creation (verification of the person done, can be synchronized, is synchronized), the transports that tell the browser where to look for it and the day
-- it was added. Not the attestation, not the client data, not the AAGUID (they could say the model of the phone or the password manager), no label written by hand, no last use.
--
-- The keys to `account` have no cascade, as `child`, `registration` and the consent of the notes (V5, V7): whoever deletes an account (`ErasureService`, `AccountsRule`) takes devices and
-- passkeys away first, in the same transaction, and a mistake is an error in a test and not rows left in silence.

alter table account add column session_generation integer not null default 0;
alter table account add column passkey_handle bytea;

alter table account add constraint account_session_generation_not_negative check (session_generation >= 0);
alter table account add constraint account_passkey_handle_unique unique (passkey_handle);
alter table account add constraint account_passkey_handle_size check (passkey_handle is null or octet_length(passkey_handle) = 64);

create table remembered_device (
    id             bigint generated always as identity primary key,
    account_id     bigint not null references account (id),
    -- 16 random bytes in base64url: 22 characters.
    selector       text   not null,
    -- SHA-256 of the 32 random bytes of the cookie.
    validator_hash bytea  not null,
    created_on     date   not null,
    -- SHA-256 of the value that the cookie had before the last change, and the moment of the change: both or none.
    previous_hash  bytea,
    rotated_at     timestamptz,
    constraint remembered_device_selector_unique unique (selector),
    constraint remembered_device_selector_format check (selector ~ '^[A-Za-z0-9_-]{22}$'),
    constraint remembered_device_validator_hash_size check (octet_length(validator_hash) = 32),
    constraint remembered_device_previous_hash_size check (previous_hash is null or octet_length(previous_hash) = 32),
    constraint remembered_device_rotation_together check ((previous_hash is null) = (rotated_at is null))
);

create index remembered_device_account on remembered_device (account_id);

create table passkey (
    -- The identifier of the credential: 16 to 1023 bytes, as WebAuthn says.
    credential_id   bytea   primary key,
    account_id      bigint  not null references account (id),
    -- The public key in COSE form, as webauthn4j reads it.
    public_key      bytea   not null,
    signature_count bigint  not null,
    uv_initialized  boolean not null,
    backup_eligible boolean not null,
    backup_state    boolean not null,
    -- The transports that the phone declared («internal», «hybrid»...), separated by commas: null when it said none.
    transports      text,
    created_on      date    not null,
    constraint passkey_credential_id_size check (octet_length(credential_id) between 16 and 1023),
    constraint passkey_public_key_not_empty check (octet_length(public_key) > 0),
    constraint passkey_signature_count_not_negative check (signature_count >= 0),
    constraint passkey_backup_state_needs_eligible check (not backup_state or backup_eligible)
);

create index passkey_account on passkey (account_id);
