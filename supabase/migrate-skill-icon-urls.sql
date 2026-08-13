-- Rewrite hero_skills.icon_url from the upstream epic7db.com CDN to the managed
-- Supabase Storage mirrors.
--
-- tools/sync-hero-catalog.mjs (mirrorSkillImages) already uploads every skill icon to:
--     Epic7/skills/<hero_code>/skill_<slot>.webp
-- and rewrites these URLs on a full sync. This script backfills rows that were
-- imported before image mirroring existed (or with --skip-image-mirror), so the
-- app stops depending on epic7db.com.
--
-- Run once in the Supabase SQL Editor. Idempotent: rows already pointing at the
-- managed URL are left untouched, and skills without an icon stay NULL.

do $$
declare
    storage_base constant text :=
        'https://biayslzufpixsyuitjus.supabase.co/storage/v1/object/public/Epic7/skills/';
    rewrote integer := 0;
    kept_null integer := 0;
begin
    update public.hero_skills
       set icon_url = storage_base || hero_code || '/skill_' || slot || '.webp'
     where icon_url is not null
       and icon_url <> storage_base || hero_code || '/skill_' || slot || '.webp';

    get diagnostics rewrote = row_count;

    select count(*) into kept_null
      from public.hero_skills
     where icon_url is null;

    raise notice 'Rewrote % hero skill icon URLs; % rows remain without an icon',
        rewrote, kept_null;
end;
$$;

-- Verify nothing still references the upstream CDN:
-- select count(*) as epic7db_remaining
--   from public.hero_skills
--  where icon_url like 'https://epic7db.com/%';
