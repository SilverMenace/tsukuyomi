-- A ceiling on rows per table, so one project cannot fill the shared database.

-- Runs once after each INSERT statement (not once per row). It counts at most
-- quota + 1 rows, so the check stays cheap however large the table is, and
-- raises our own error code if the table is over. Raising an error rolls the
-- insert back. SECURITY DEFINER because the count must see every row, not
-- only the rows the inserting user is allowed to see.
create function tsk.enforce_row_quota() returns trigger
    language plpgsql security definer set search_path = pg_catalog
    as $$
declare
    quota int := tg_argv[0]::int;
    found int;
begin
    execute format('select count(*) from (select 1 from %I.%I limit %s) rows',
                   tg_table_schema, tg_table_name, quota + 1) into found;
    if found > quota then
        raise exception 'table "%" is full: it may hold at most % rows', tg_table_name, quota
            using errcode = 'TSK01';
    end if;
    return null;
end $$;

-- Tables that exist already. New tables get the trigger when they are created
-- (same statement: RowQuota.java). The built-in users table is limited by the
-- end-user quota in AuthService instead.
do $$
declare
    t record;
begin
    for t in select p.schema_name, m.name
             from tsk_platform.table_meta m join tsk_platform.project p on p.id = m.project_id
             where not m.builtin loop
        execute format('create trigger tsk_quota after insert on %I.%I
            for each statement execute function tsk.enforce_row_quota(''10000'')', t.schema_name, t.name);
    end loop;
end $$;
