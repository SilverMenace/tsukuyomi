-- A column can point at another table of the same project (a foreign key to its id).
-- The data API uses this to expand related records.
alter table tsk_platform.column_meta
    add column ref_table text,
    add column on_delete text check (on_delete in ('restrict', 'cascade', 'set_null')),
    add constraint column_meta_ref_complete check ((ref_table is null) = (on_delete is null));
