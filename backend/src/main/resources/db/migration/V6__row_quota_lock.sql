-- Closes a gap in the row quota found by a security review.
--
-- Two inserts running at the same moment each counted the rows that were
-- committed before they started, did not see each other's new rows, and both
-- passed: a table at 9,999 could end at 10,001 or, with many at once, well over.
--
-- Now the trigger first takes a lock that belongs to this one table and is
-- held until the transaction ends. A second insert waits there; when it gets
-- the lock the first has committed, and its count (a fresh look at the table,
-- as every statement gets in READ COMMITTED) includes those rows.
-- The cost: inserts into the same table finish one after another.
create or replace function tsk.enforce_row_quota() returns trigger
    language plpgsql security definer set search_path = pg_catalog
    as $$
declare
    quota int := tg_argv[0]::int;
    found int;
begin
    -- An "advisory" lock is identified by a number we choose; the table's
    -- internal id is unique per table. "xact" = released at commit or rollback.
    perform pg_advisory_xact_lock(tg_relid::bigint);
    execute format('select count(*) from (select 1 from %I.%I limit %s) rows',
                   tg_table_schema, tg_table_name, quota + 1) into found;
    if found > quota then
        raise exception 'table "%" is full: it may hold at most % rows', tg_table_name, quota
            using errcode = 'TSK01';
    end if;
    return null;
end $$;
