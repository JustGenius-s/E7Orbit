-- Adds authenticated, administrator-only Wiki editing.
-- Run this after schema.sql, then add an Auth user to public.wiki_editors.

create table if not exists public.wiki_editors (
    user_id uuid primary key references auth.users(id) on delete cascade,
    created_at timestamptz not null default now()
);

alter table public.wiki_editors enable row level security;
revoke all on table public.wiki_editors from anon, authenticated;

create table if not exists public.wiki_hero_overrides (
    hero_code text primary key references public.hero_catalog(code) on delete cascade,
    updated_by uuid not null references auth.users(id) on delete restrict,
    updated_at timestamptz not null default now()
);

alter table public.wiki_hero_overrides enable row level security;
revoke all on table public.wiki_hero_overrides from anon, authenticated;

create table if not exists public.wiki_artifact_overrides (
    artifact_code text primary key references public.artifact_catalog(code) on delete cascade,
    updated_by uuid not null references auth.users(id) on delete restrict,
    updated_at timestamptz not null default now()
);

alter table public.wiki_artifact_overrides enable row level security;
revoke all on table public.wiki_artifact_overrides from anon, authenticated;

create or replace function public.is_wiki_editor()
returns boolean
language sql
stable
security definer
set search_path = ''
as $$
    select auth.uid() is not null
        and exists (
            select 1
            from public.wiki_editors
            where user_id = auth.uid()
        );
$$;

revoke all on function public.is_wiki_editor() from public, anon;
grant execute on function public.is_wiki_editor() to authenticated;

drop policy if exists "Wiki editors can read override markers"
    on public.wiki_hero_overrides;
create policy "Wiki editors can read override markers"
    on public.wiki_hero_overrides for select
    to authenticated
    using (public.is_wiki_editor());

drop policy if exists "Wiki editors can insert override markers"
    on public.wiki_hero_overrides;
create policy "Wiki editors can insert override markers"
    on public.wiki_hero_overrides for insert
    to authenticated
    with check (
        public.is_wiki_editor()
        and updated_by = auth.uid()
    );

drop policy if exists "Wiki editors can update override markers"
    on public.wiki_hero_overrides;
create policy "Wiki editors can update override markers"
    on public.wiki_hero_overrides for update
    to authenticated
    using (public.is_wiki_editor())
    with check (
        public.is_wiki_editor()
        and updated_by = auth.uid()
    );

drop policy if exists "Wiki editors can read artifact override markers"
    on public.wiki_artifact_overrides;
create policy "Wiki editors can read artifact override markers"
    on public.wiki_artifact_overrides for select
    to authenticated
    using (public.is_wiki_editor());

drop policy if exists "Wiki editors can insert artifact override markers"
    on public.wiki_artifact_overrides;
create policy "Wiki editors can insert artifact override markers"
    on public.wiki_artifact_overrides for insert
    to authenticated
    with check (
        public.is_wiki_editor()
        and updated_by = auth.uid()
    );

drop policy if exists "Wiki editors can update artifact override markers"
    on public.wiki_artifact_overrides;
create policy "Wiki editors can update artifact override markers"
    on public.wiki_artifact_overrides for update
    to authenticated
    using (public.is_wiki_editor())
    with check (
        public.is_wiki_editor()
        and updated_by = auth.uid()
    );

drop policy if exists "Wiki editors can insert heroes" on public.hero_catalog;
create policy "Wiki editors can insert heroes"
    on public.hero_catalog for insert
    to authenticated
    with check (public.is_wiki_editor());

drop policy if exists "Wiki editors can update heroes" on public.hero_catalog;
create policy "Wiki editors can update heroes"
    on public.hero_catalog for update
    to authenticated
    using (public.is_wiki_editor())
    with check (public.is_wiki_editor());

drop policy if exists "Wiki editors can insert artifacts" on public.artifact_catalog;
create policy "Wiki editors can insert artifacts"
    on public.artifact_catalog for insert
    to authenticated
    with check (public.is_wiki_editor());

drop policy if exists "Wiki editors can update artifacts" on public.artifact_catalog;
create policy "Wiki editors can update artifacts"
    on public.artifact_catalog for update
    to authenticated
    using (public.is_wiki_editor())
    with check (public.is_wiki_editor());

drop policy if exists "Wiki editors can insert skills" on public.hero_skills;
create policy "Wiki editors can insert skills"
    on public.hero_skills for insert
    to authenticated
    with check (public.is_wiki_editor());

drop policy if exists "Wiki editors can update skills" on public.hero_skills;
create policy "Wiki editors can update skills"
    on public.hero_skills for update
    to authenticated
    using (public.is_wiki_editor())
    with check (public.is_wiki_editor());

drop policy if exists "Wiki editors can delete skills" on public.hero_skills;
create policy "Wiki editors can delete skills"
    on public.hero_skills for delete
    to authenticated
    using (public.is_wiki_editor());

drop policy if exists "Wiki editors can insert exclusive equipment"
    on public.hero_exclusive_equipment;
create policy "Wiki editors can insert exclusive equipment"
    on public.hero_exclusive_equipment for insert
    to authenticated
    with check (public.is_wiki_editor());

drop policy if exists "Wiki editors can update exclusive equipment"
    on public.hero_exclusive_equipment;
create policy "Wiki editors can update exclusive equipment"
    on public.hero_exclusive_equipment for update
    to authenticated
    using (public.is_wiki_editor())
    with check (public.is_wiki_editor());

drop policy if exists "Wiki editors can delete exclusive equipment"
    on public.hero_exclusive_equipment;
create policy "Wiki editors can delete exclusive equipment"
    on public.hero_exclusive_equipment for delete
    to authenticated
    using (public.is_wiki_editor());

revoke insert, update, delete on table public.hero_catalog from anon;
revoke insert, update, delete on table public.hero_skills from anon;
revoke insert, update, delete on table public.hero_exclusive_equipment from anon;
revoke insert, update, delete on table public.artifact_catalog from anon;
grant insert, update on table public.hero_catalog to authenticated;
grant insert, update on table public.artifact_catalog to authenticated;
grant select, insert, update on table public.wiki_hero_overrides to authenticated;
grant select, insert, update on table public.wiki_artifact_overrides to authenticated;
grant insert, update, delete on table public.hero_skills to authenticated;
grant insert, update, delete on table public.hero_exclusive_equipment to authenticated;

create or replace function public.save_wiki_hero(
    p_hero_code text,
    p_hero jsonb,
    p_skills jsonb default '[]'::jsonb,
    p_exclusive_equipment jsonb default null
)
returns void
language plpgsql
security invoker
set search_path = ''
as $$
declare
    skill jsonb;
begin
    if not public.is_wiki_editor() then
        raise exception 'Wiki editor permission required' using errcode = '42501';
    end if;
    if nullif(trim(p_hero_code), '') is null then
        raise exception 'Hero code is required' using errcode = '22023';
    end if;
    if jsonb_typeof(p_hero) <> 'object' then
        raise exception 'Hero payload must be an object' using errcode = '22023';
    end if;
    if nullif(trim(p_hero ->> 'name'), '') is null then
        raise exception 'Hero name is required' using errcode = '22023';
    end if;
    if jsonb_typeof(coalesce(p_skills, '[]'::jsonb)) <> 'array' then
        raise exception 'Skills payload must be an array' using errcode = '22023';
    end if;
    if jsonb_array_length(coalesce(p_skills, '[]'::jsonb)) > 5 then
        raise exception 'A hero can have at most five skills' using errcode = '22023';
    end if;
    if exists (
        select 1
        from jsonb_array_elements(coalesce(p_skills, '[]'::jsonb)) as item
        where (item ->> 'slot')::integer not between 1 and 5
            or nullif(trim(item ->> 'name'), '') is null
    ) then
        raise exception 'Every skill requires a unique slot from 1 to 5 and a name'
            using errcode = '22023';
    end if;
    if (
        select count(distinct (item ->> 'slot')::integer)
        from jsonb_array_elements(coalesce(p_skills, '[]'::jsonb)) as item
    ) <> jsonb_array_length(coalesce(p_skills, '[]'::jsonb)) then
        raise exception 'Skill slots must be unique' using errcode = '22023';
    end if;

    insert into public.hero_catalog (
        code,
        name,
        rarity,
        attribute,
        role,
        zodiac,
        description,
        icon_url,
        thumbnail_url,
        image_url,
        stats_attack,
        stats_health,
        stats_defense,
        stats_speed,
        stats_critical_chance,
        stats_critical_damage,
        stats_effectiveness,
        stats_effect_resistance,
        stats_combat_power,
        source,
        updated_at
    ) values (
        p_hero_code,
        trim(p_hero ->> 'name'),
        (p_hero ->> 'rarity')::integer,
        coalesce(p_hero ->> 'attribute', ''),
        coalesce(p_hero ->> 'role', ''),
        nullif(trim(p_hero ->> 'zodiac'), ''),
        nullif(trim(p_hero ->> 'description'), ''),
        nullif(trim(p_hero ->> 'icon_url'), ''),
        nullif(trim(p_hero ->> 'thumbnail_url'), ''),
        nullif(trim(p_hero ->> 'image_url'), ''),
        (p_hero ->> 'stats_attack')::integer,
        (p_hero ->> 'stats_health')::integer,
        (p_hero ->> 'stats_defense')::integer,
        (p_hero ->> 'stats_speed')::integer,
        (p_hero ->> 'stats_critical_chance')::integer,
        (p_hero ->> 'stats_critical_damage')::integer,
        (p_hero ->> 'stats_effectiveness')::integer,
        (p_hero ->> 'stats_effect_resistance')::integer,
        (p_hero ->> 'stats_combat_power')::integer,
        'wiki',
        now()
    )
    on conflict (code) do update
    set name = excluded.name,
        rarity = excluded.rarity,
        attribute = excluded.attribute,
        role = excluded.role,
        zodiac = excluded.zodiac,
        description = excluded.description,
        icon_url = excluded.icon_url,
        thumbnail_url = excluded.thumbnail_url,
        image_url = excluded.image_url,
        stats_attack = excluded.stats_attack,
        stats_health = excluded.stats_health,
        stats_defense = excluded.stats_defense,
        stats_speed = excluded.stats_speed,
        stats_critical_chance = excluded.stats_critical_chance,
        stats_critical_damage = excluded.stats_critical_damage,
        stats_effectiveness = excluded.stats_effectiveness,
        stats_effect_resistance = excluded.stats_effect_resistance,
        stats_combat_power = excluded.stats_combat_power,
        source = excluded.source,
        updated_at = excluded.updated_at;

    insert into public.wiki_hero_overrides (
        hero_code,
        updated_by,
        updated_at
    ) values (
        p_hero_code,
        auth.uid(),
        now()
    )
    on conflict (hero_code) do update
    set updated_by = excluded.updated_by,
        updated_at = excluded.updated_at;

    delete from public.hero_skills where hero_code = p_hero_code;
    for skill in
        select value
        from jsonb_array_elements(coalesce(p_skills, '[]'::jsonb))
    loop
        insert into public.hero_skills (
            hero_code,
            slot,
            name,
            icon_url,
            description,
            enhanced_description,
            cooldown,
            soul_gain,
            soul_requirement,
            soul_description,
            attack_rate,
            pow,
            is_passive,
            can_enhance,
            values,
            enhancements,
            buff_slugs,
            debuff_slugs,
            source,
            updated_at
        ) values (
            p_hero_code,
            (skill ->> 'slot')::integer,
            trim(skill ->> 'name'),
            nullif(trim(skill ->> 'icon_url'), ''),
            nullif(trim(skill ->> 'description'), ''),
            nullif(trim(skill ->> 'enhanced_description'), ''),
            (skill ->> 'cooldown')::integer,
            (skill ->> 'soul_gain')::integer,
            (skill ->> 'soul_requirement')::integer,
            nullif(trim(skill ->> 'soul_description'), ''),
            (skill ->> 'attack_rate')::double precision,
            (skill ->> 'pow')::double precision,
            coalesce((skill ->> 'is_passive')::boolean, false),
            coalesce((skill ->> 'can_enhance')::boolean, false),
            case
                when jsonb_typeof(skill -> 'values') = 'array' then skill -> 'values'
                else '[]'::jsonb
            end,
            case
                when jsonb_typeof(skill -> 'enhancements') = 'array' then skill -> 'enhancements'
                else '[]'::jsonb
            end,
            case
                when jsonb_typeof(skill -> 'buff_slugs') = 'array' then
                    array(select jsonb_array_elements_text(skill -> 'buff_slugs'))
                else '{}'::text[]
            end,
            case
                when jsonb_typeof(skill -> 'debuff_slugs') = 'array' then
                    array(select jsonb_array_elements_text(skill -> 'debuff_slugs'))
                else '{}'::text[]
            end,
            'wiki',
            now()
        );
    end loop;

    delete from public.hero_exclusive_equipment where hero_code = p_hero_code;
    if p_exclusive_equipment is not null
        and jsonb_typeof(p_exclusive_equipment) <> 'null' then
        if jsonb_typeof(p_exclusive_equipment) <> 'object' then
            raise exception 'Exclusive equipment payload must be an object'
                using errcode = '22023';
        end if;
        insert into public.hero_exclusive_equipment (
            code,
            hero_code,
            name,
            description,
            icon_url,
            stat_type,
            stat_min,
            stat_max,
            stat_percent,
            enhancements,
            source,
            updated_at
        ) values (
            'ee-' || p_hero_code,
            p_hero_code,
            trim(p_exclusive_equipment ->> 'name'),
            nullif(trim(p_exclusive_equipment ->> 'description'), ''),
            trim(p_exclusive_equipment ->> 'icon_url'),
            p_exclusive_equipment ->> 'stat_type',
            (p_exclusive_equipment ->> 'stat_min')::double precision,
            (p_exclusive_equipment ->> 'stat_max')::double precision,
            coalesce((p_exclusive_equipment ->> 'stat_percent')::boolean, false),
            p_exclusive_equipment -> 'enhancements',
            'wiki',
            now()
        );
    end if;
end;
$$;

revoke all on function public.save_wiki_hero(text, jsonb, jsonb, jsonb)
    from public, anon;
grant execute on function public.save_wiki_hero(text, jsonb, jsonb, jsonb)
    to authenticated;

create or replace function public.save_wiki_artifact(
    p_artifact_code text,
    p_artifact jsonb
)
returns void
language plpgsql
security invoker
set search_path = ''
as $$
begin
    if not public.is_wiki_editor() then
        raise exception 'Wiki editor permission required' using errcode = '42501';
    end if;
    if nullif(trim(p_artifact_code), '') is null then
        raise exception 'Artifact code is required' using errcode = '22023';
    end if;
    if jsonb_typeof(p_artifact) <> 'object' then
        raise exception 'Artifact payload must be an object' using errcode = '22023';
    end if;
    if nullif(trim(p_artifact ->> 'name'), '') is null then
        raise exception 'Artifact name is required' using errcode = '22023';
    end if;

    insert into public.artifact_catalog (
        code,
        name,
        rarity,
        role,
        description,
        max_description,
        lore,
        image_url,
        icon_url,
        stats_attack,
        stats_health,
        stats_defense,
        base_attack,
        base_health,
        source,
        updated_at
    ) values (
        p_artifact_code,
        trim(p_artifact ->> 'name'),
        (p_artifact ->> 'rarity')::integer,
        coalesce(trim(p_artifact ->> 'role'), ''),
        nullif(trim(p_artifact ->> 'description'), ''),
        nullif(trim(p_artifact ->> 'max_description'), ''),
        nullif(trim(p_artifact ->> 'lore'), ''),
        nullif(trim(p_artifact ->> 'image_url'), ''),
        nullif(trim(p_artifact ->> 'icon_url'), ''),
        (p_artifact ->> 'stats_attack')::integer,
        (p_artifact ->> 'stats_health')::integer,
        (p_artifact ->> 'stats_defense')::integer,
        (p_artifact ->> 'base_attack')::integer,
        (p_artifact ->> 'base_health')::integer,
        'wiki',
        now()
    )
    on conflict (code) do update
    set name = excluded.name,
        rarity = excluded.rarity,
        role = excluded.role,
        description = excluded.description,
        max_description = excluded.max_description,
        lore = excluded.lore,
        image_url = excluded.image_url,
        icon_url = excluded.icon_url,
        stats_attack = excluded.stats_attack,
        stats_health = excluded.stats_health,
        stats_defense = excluded.stats_defense,
        base_attack = excluded.base_attack,
        base_health = excluded.base_health,
        source = excluded.source,
        updated_at = excluded.updated_at;

    insert into public.wiki_artifact_overrides (
        artifact_code,
        updated_by,
        updated_at
    ) values (
        p_artifact_code,
        auth.uid(),
        now()
    )
    on conflict (artifact_code) do update
    set updated_by = excluded.updated_by,
        updated_at = excluded.updated_at;
end;
$$;

revoke all on function public.save_wiki_artifact(text, jsonb)
    from public, anon;
grant execute on function public.save_wiki_artifact(text, jsonb)
    to authenticated;

-- Bootstrap an editor after creating the user in Supabase Authentication:
-- insert into public.wiki_editors (user_id)
-- select id from auth.users where email = 'admin@example.com'
-- on conflict (user_id) do nothing;
