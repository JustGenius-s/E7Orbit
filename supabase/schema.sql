-- E7 Orbit maintained hero catalog.
-- Run this in Supabase SQL Editor before importing data.
-- The Android app only needs the publishable/anon key and read access.

create table if not exists public.hero_catalog (
    code text primary key,
    name text not null,
    rarity integer,
    attribute text not null default '',
    role text not null default '',
    zodiac text,
    description text,
    icon_url text,
    thumbnail_url text,
    image_url text,
    stats_attack integer,
    stats_health integer,
    stats_defense integer,
    stats_speed integer,
    stats_critical_chance integer,
    stats_critical_damage integer,
    stats_effectiveness integer,
    stats_effect_resistance integer,
    stats_combat_power integer,
    source text not null default 'community-import',
    source_updated_at timestamptz,
    updated_at timestamptz not null default now()
);

create table if not exists public.hero_skills (
    hero_code text not null references public.hero_catalog(code) on delete cascade,
    slot integer not null check (slot between 1 and 5), -- 1-3 base, 4-5 transformed/extra skills (e.g. Tamarinne)
    name text not null default '',
    icon_url text,
    description text,
    enhanced_description text,
    cooldown integer,
    soul_gain integer,
    soul_requirement integer,
    soul_description text,
    attack_rate double precision,
    pow double precision,
    is_passive boolean not null default false,
    can_enhance boolean not null default false,
    values jsonb not null default '[]'::jsonb,
    enhancements jsonb not null default '[]'::jsonb,
    source text not null default 'epic7db',
    source_updated_at timestamptz,
    updated_at timestamptz not null default now(),
    primary key (hero_code, slot)
);

create index if not exists hero_skills_hero_code_idx on public.hero_skills(hero_code);

alter table public.hero_catalog enable row level security;
alter table public.hero_skills enable row level security;

drop policy if exists "Public can read hero catalog" on public.hero_catalog;
create policy "Public can read hero catalog"
    on public.hero_catalog for select
    to anon, authenticated
    using (true);

drop policy if exists "Public can read hero skills" on public.hero_skills;
create policy "Public can read hero skills"
    on public.hero_skills for select
    to anon, authenticated
    using (true);

create table if not exists public.artifact_catalog (
    code text primary key,
    name text not null,
    rarity integer,
    role text not null default '',
    description text,
    max_description text,
    lore text,
    image_url text,
    icon_url text,
    stats_attack integer,
    stats_health integer,
    stats_defense integer,
    source text not null default 'epic7db',
    source_updated_at timestamptz,
    updated_at timestamptz not null default now()
);

alter table public.artifact_catalog enable row level security;

drop policy if exists "Public can read artifact catalog" on public.artifact_catalog;
create policy "Public can read artifact catalog"
    on public.artifact_catalog for select
    to anon, authenticated
    using (true);

-- The service_role key bypasses RLS for tools/sync-hero-catalog.mjs.
-- Do not create anonymous INSERT/UPDATE policies: mobile clients must be read-only.
