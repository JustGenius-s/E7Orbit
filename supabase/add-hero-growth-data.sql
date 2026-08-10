-- Add Epic7DB hero growth data without introducing additional relation tables.
-- Run in Supabase SQL Editor before tools/sync-hero-catalog.mjs --growth-only.

begin;

alter table public.hero_catalog
    add column if not exists awakenings jsonb not null default '[]'::jsonb,
    add column if not exists memory_imprint jsonb not null default '{}'::jsonb;

-- The skill enhancement-cost field was intentionally removed from the catalog.
alter table public.hero_skills
    drop column if exists enhancement_costs;

commit;
