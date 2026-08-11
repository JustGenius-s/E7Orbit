-- Adds the maintained, intentionally partial GameKee exclusive-equipment catalog.
-- Run after schema.sql. Rows are read-only to mobile clients.

create table if not exists public.hero_exclusive_equipment (
    code text primary key,
    hero_code text not null unique references public.hero_catalog(code) on delete cascade,
    name text not null check (length(trim(name)) > 0),
    description text,
    icon_url text not null check (length(trim(icon_url)) > 0),
    stat_type text not null check (stat_type in (
        'attack', 'health', 'defense', 'speed',
        'critical_chance', 'critical_damage',
        'effectiveness', 'effect_resistance'
    )),
    stat_min double precision not null,
    stat_max double precision not null check (stat_max >= stat_min),
    stat_percent boolean not null default false,
    enhancements jsonb not null check (
        case when jsonb_typeof(enhancements) = 'array'
            then jsonb_array_length(enhancements) = 3
                and enhancements @> '[{"option": 1}, {"option": 2}, {"option": 3}]'::jsonb
            else false
        end
    ),
    source text not null default 'gamekee',
    source_updated_at timestamptz,
    updated_at timestamptz not null default now(),
    check (code = 'ee-' || hero_code)
);

create index if not exists hero_exclusive_equipment_hero_code_idx
    on public.hero_exclusive_equipment(hero_code);

alter table public.hero_exclusive_equipment enable row level security;

drop policy if exists "Public can read hero exclusive equipment"
    on public.hero_exclusive_equipment;
create policy "Public can read hero exclusive equipment"
    on public.hero_exclusive_equipment for select
    to anon, authenticated
    using (true);
