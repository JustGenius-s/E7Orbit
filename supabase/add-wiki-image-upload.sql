-- Allow authenticated Wiki editors to upload and overwrite images in the Epic7
-- bucket. This backs the in-app Wiki editor's "upload image to replace an existing
-- icon/art" feature. Bulk imports keep using the service_role key; this policy is
-- only for authenticated administrators (public.is_wiki_editor()).
--
-- Run once in the Supabase SQL Editor.

drop policy if exists "Wiki editors can upload images" on storage.objects;
create policy "Wiki editors can upload images"
on storage.objects for insert
to authenticated
with check (
    public.is_wiki_editor()
    and bucket_id = 'Epic7'
    and (storage.foldername(name))[1] in (
        'skills',
        'heroes',
        'artifacts',
        'status-effects',
        'exclusive-equipment'
    )
);

drop policy if exists "Wiki editors can overwrite images" on storage.objects;
create policy "Wiki editors can overwrite images"
on storage.objects for update
to authenticated
using (
    public.is_wiki_editor()
    and bucket_id = 'Epic7'
    and (storage.foldername(name))[1] in (
        'skills',
        'heroes',
        'artifacts',
        'status-effects',
        'exclusive-equipment'
    )
)
with check (
    public.is_wiki_editor()
    and bucket_id = 'Epic7'
    and (storage.foldername(name))[1] in (
        'skills',
        'heroes',
        'artifacts',
        'status-effects',
        'exclusive-equipment'
    )
);
