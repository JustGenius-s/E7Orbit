-- Move shared buff/debuff metadata into status_effect_catalog while keeping
-- ordered slug arrays directly on hero_skills. Run once in Supabase SQL Editor
-- while the legacy hero_skills.buffs/debuffs JSONB columns still exist.

begin;

create table if not exists public.status_effect_catalog (
    slug text primary key,
    label text not null,
    description text,
    icon_url text,
    source text not null default 'gamedatabase',
    source_updated_at timestamptz,
    updated_at timestamptz not null default now()
);

alter table public.hero_skills
    add column if not exists buff_slugs text[] not null default '{}',
    add column if not exists debuff_slugs text[] not null default '{}';

create index if not exists hero_skills_buff_slugs_idx
    on public.hero_skills using gin (buff_slugs);
create index if not exists hero_skills_debuff_slugs_idx
    on public.hero_skills using gin (debuff_slugs);

alter table public.status_effect_catalog enable row level security;

drop policy if exists "Public can read status effect catalog" on public.status_effect_catalog;
create policy "Public can read status effect catalog"
    on public.status_effect_catalog for select
    to anon, authenticated
    using (true);

with effect_source as (
    select effect.value as effect
    from public.hero_skills
    cross join lateral jsonb_array_elements(coalesce(buffs, '[]'::jsonb)) as effect(value)
    union all
    select effect.value as effect
    from public.hero_skills
    cross join lateral jsonb_array_elements(coalesce(debuffs, '[]'::jsonb)) as effect(value)
), ranked_effects as (
    select
        effect ->> 'slug' as slug,
        coalesce(nullif(effect ->> 'label', ''), effect ->> 'slug') as label,
        nullif(effect ->> 'description', '') as description,
        coalesce(effect ->> 'icon_url', effect ->> 'iconUrl') as icon_url,
        row_number() over (
            partition by effect ->> 'slug'
            order by
                (nullif(effect ->> 'description', '') is not null) desc,
                (coalesce(effect ->> 'icon_url', effect ->> 'iconUrl') is not null) desc
        ) as row_rank
    from effect_source
    where nullif(effect ->> 'slug', '') is not null
)
insert into public.status_effect_catalog (
    slug,
    label,
    description,
    icon_url,
    source,
    source_updated_at,
    updated_at
)
select
    slug,
    label,
    description,
    icon_url,
    'gamedatabase',
    now(),
    now()
from ranked_effects
where row_rank = 1
on conflict (slug) do update set
    label = excluded.label,
    description = coalesce(excluded.description, public.status_effect_catalog.description),
    icon_url = coalesce(excluded.icon_url, public.status_effect_catalog.icon_url),
    source_updated_at = excluded.source_updated_at,
    updated_at = excluded.updated_at;

update public.hero_skills as skill
set
    buff_slugs = coalesce(
        (
            select array_agg(effect.value ->> 'slug' order by effect.ordinality)
            from jsonb_array_elements(coalesce(skill.buffs, '[]'::jsonb))
                with ordinality as effect(value, ordinality)
            where nullif(effect.value ->> 'slug', '') is not null
        ),
        array[]::text[]
    ),
    debuff_slugs = coalesce(
        (
            select array_agg(effect.value ->> 'slug' order by effect.ordinality)
            from jsonb_array_elements(coalesce(skill.debuffs, '[]'::jsonb))
                with ordinality as effect(value, ordinality)
            where nullif(effect.value ->> 'slug', '') is not null
        ),
        array[]::text[]
    );

alter table public.hero_skills
    drop column buffs,
    drop column debuffs;

commit;
