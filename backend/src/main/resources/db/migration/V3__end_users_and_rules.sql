-- End users of projects, and permission rules per table.

-- A tiny public schema holding the one function every row-level security
-- policy calls: "which end user is making this request?". The data API sets
-- tsk.user_id for the duration of each transaction (see TenantTransaction).
create schema tsk;
grant usage on schema tsk to public;

create function tsk.uid() returns uuid
    language sql stable
    as $$ select nullif(current_setting('tsk.user_id', true), '')::uuid $$;

-- Tables the platform creates itself (currently only "users") are marked.
alter table tsk_platform.table_meta add column builtin boolean not null default false;

-- One rule per table and operation. No row means "nobody".
create table tsk_platform.rule (
    table_id     uuid not null references tsk_platform.table_meta (id) on delete cascade,
    operation    text not null check (operation in ('select', 'insert', 'update', 'delete')),
    kind         text not null check (kind in ('anyone', 'authenticated', 'owner')),
    owner_column text,
    primary key (table_id, operation),
    check ((kind = 'owner') = (owner_column is not null))
);

-- Credentials of end users. The public profile (id, email) lives in the
-- project's own "users" table; the password hash stays here, in a schema no
-- tenant role can read.
create table tsk_platform.end_user (
    project_id    uuid not null references tsk_platform.project (id) on delete cascade,
    user_id       uuid not null,
    email         text not null,
    password_hash text not null,
    created_at    timestamptz not null default now(),
    primary key (project_id, user_id),
    unique (project_id, email)
);

-- Refresh tokens: long-lived, stored hashed, replaced every time they are used.
create table tsk_platform.end_user_session (
    token_hash text primary key,
    project_id uuid not null,
    user_id    uuid not null,
    expires_at timestamptz not null,
    foreign key (project_id, user_id) references tsk_platform.end_user (project_id, user_id) on delete cascade
);

-- Projects created before this migration get their "users" table now.
-- New projects get it in ProjectService.create (same statements: BuiltinTables).
do $$
declare
    p record;
    t uuid;
begin
    for p in select id, schema_name from tsk_platform.project loop
        execute format('create table %I.users (
            id uuid primary key default uuidv7(),
            created_at timestamptz not null default now(),
            email text not null constraint users_email_key unique)', p.schema_name);
        execute format('alter table %I.users enable row level security', p.schema_name);
        execute format('alter table %I.users force row level security', p.schema_name);
        execute format('grant select, update on %I.users to %I, %I, %I', p.schema_name,
            p.schema_name || '_anon', p.schema_name || '_user', p.schema_name || '_service');
        execute format('create policy service_all on %I.users for all to %I using (true) with check (true)',
            p.schema_name, p.schema_name || '_service');
        execute format('create policy rule_select on %I.users for select to %I using (id = tsk.uid())',
            p.schema_name, p.schema_name || '_user');

        insert into tsk_platform.table_meta (project_id, name, builtin) values (p.id, 'users', true)
            returning id into t;
        insert into tsk_platform.column_meta (table_id, name, type, nullable, is_unique, is_system, position) values
            (t, 'id', 'uuid', false, true, true, 0),
            (t, 'created_at', 'timestamptz', false, false, true, 1),
            (t, 'email', 'text', false, true, true, 2);
        insert into tsk_platform.rule (table_id, operation, kind, owner_column) values (t, 'select', 'owner', 'id');
    end loop;
end $$;
