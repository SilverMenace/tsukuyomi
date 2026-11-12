-- Live updates: every change to a row of a project table is recorded here,
-- and announced with NOTIFY so the server hears about it immediately.

-- The log of changes ("outbox"). It says WHICH row changed; subscribers get
-- the row itself by reading it under their own identity, so
-- row-level security decides who sees what. Kept for about ten minutes, so a
-- client that lost its connection can ask for what it missed.
create table tsk_platform.change_event (
    id          bigint generated always as identity primary key,
    schema_name text not null,
    table_name  text not null,
    op          text not null check (op in ('insert', 'update', 'delete')),
    row_id      uuid not null,
    -- The id of the transaction that made the change.
    txid        bigint not null,
    -- The oldest transaction still running when the change was made. Every
    -- transaction with a smaller id had already finished. This is what makes
    -- "give me what I missed" correct; see DECISIONS.md D33.
    oldest_running bigint not null,
    -- Deletes only: the row as it was. Never sent to anyone. It is kept so the
    -- server can tell whom the row belonged to, and announce the delete only
    -- to those who could have seen the row (DECISIONS.md D32).
    old_row     jsonb,
    created_at  timestamptz not null default clock_timestamp()
);
create index change_event_replay on tsk_platform.change_event (schema_name, table_name, txid);
create index change_event_age on tsk_platform.change_event (created_at);

-- One row: events from transactions below this id may already have been
-- deleted. A client asking for older history is told to reload instead.
create table tsk_platform.change_floor (
    only_row boolean primary key default true check (only_row),
    txid     bigint not null
);
insert into tsk_platform.change_floor (txid) values (0);

-- Runs after every insert, update and delete on a project table. It is
-- SECURITY DEFINER: it runs with the rights of its owner (the platform), so it
-- can write the log although the role making the change cannot even see it.
create function tsk.record_change() returns trigger
    language plpgsql security definer set search_path = pg_catalog
    as $$
declare
    rid  uuid;
    gone jsonb;
    eid  bigint;
    tx   bigint := pg_current_xact_id()::text::bigint;
    low  bigint := pg_snapshot_xmin(pg_current_snapshot())::text::bigint;
    kind text := lower(tg_op);
begin
    if tg_op = 'DELETE' then
        rid := old.id;
        gone := to_jsonb(old);
    else
        rid := new.id;
    end if;
    insert into tsk_platform.change_event (schema_name, table_name, op, row_id, txid, oldest_running, old_row)
        values (tg_table_schema, tg_table_name, kind, rid, tx, low, gone)
        returning id into eid;
    -- Delivered to listeners when (and only if) this transaction commits.
    perform pg_notify('tsk_changes', json_build_object(
        'id', eid, 'schema', tg_table_schema, 'table', tg_table_name,
        'op', kind, 'row', rid, 'txid', tx, 'xmin', low)::text);
    return null;
end $$;

-- Tables that exist already get the trigger now. New tables get it when they
-- are created (same statement: ChangeTrigger.java).
do $$
declare
    t record;
begin
    for t in select p.schema_name, m.name
             from tsk_platform.table_meta m join tsk_platform.project p on p.id = m.project_id loop
        execute format('create trigger tsk_change after insert or update or delete on %I.%I
            for each row execute function tsk.record_change()', t.schema_name, t.name);
    end loop;
end $$;
