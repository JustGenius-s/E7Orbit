-- Atomically replace status-effect catalog keys and all hero-skill references.
-- Install once in the Supabase SQL Editor before running:
-- npm run sync:buff-icons -- --source=... --apply

create or replace function public.replace_status_effect_catalog(
    p_catalog jsonb,
    p_skills jsonb,
    p_obsolete_slugs text[]
)
returns jsonb
language plpgsql
security definer
set search_path = public
as $$
declare
    catalog_row jsonb;
    skill_row jsonb;
    target_slug text;
    target_icon_url text;
    target_buff_slugs text[];
    target_debuff_slugs text[];
    affected integer;
    catalog_count integer := 0;
    skill_count integer := 0;
begin
    if auth.role() <> 'service_role' then
        raise exception 'Service-role access required' using errcode = '42501';
    end if;
    if jsonb_typeof(p_catalog) <> 'array' or jsonb_typeof(p_skills) <> 'array' then
        raise exception 'Catalog and skills payloads must be arrays' using errcode = '22023';
    end if;
    if exists (
        select 1
        from jsonb_array_elements(p_catalog) as item(value)
        group by item.value ->> 'slug'
        having count(*) > 1
    ) then
        raise exception 'Catalog payload contains duplicate slugs' using errcode = '22023';
    end if;

    for catalog_row in select value from jsonb_array_elements(p_catalog)
    loop
        target_slug := nullif(catalog_row ->> 'slug', '');
        target_icon_url := nullif(catalog_row ->> 'icon_url', '');
        if target_slug is null or target_slug !~ '^[a-z0-9_]+$' or target_slug like 'gamekee\_%' escape '\' then
            raise exception 'Invalid official status-effect slug: %', target_slug using errcode = '22023';
        end if;
        if target_icon_url is not null and target_icon_url !~ ('/' || target_slug || '\.png$') then
            raise exception 'Status-effect key/icon mismatch: % -> %', target_slug, target_icon_url
                using errcode = '22023';
        end if;

        insert into public.status_effect_catalog (
            slug,
            label,
            description,
            icon_url,
            source,
            source_updated_at,
            updated_at
        ) values (
            target_slug,
            coalesce(nullif(catalog_row ->> 'label', ''), target_slug),
            nullif(catalog_row ->> 'description', ''),
            target_icon_url,
            coalesce(nullif(catalog_row ->> 'source', ''), 'gamedatabase'),
            coalesce((catalog_row ->> 'source_updated_at')::timestamptz, now()),
            now()
        )
        on conflict (slug) do update
        set label = excluded.label,
            description = excluded.description,
            icon_url = excluded.icon_url,
            source = excluded.source,
            source_updated_at = excluded.source_updated_at,
            updated_at = excluded.updated_at;
        catalog_count := catalog_count + 1;
    end loop;

    for skill_row in select value from jsonb_array_elements(p_skills)
    loop
        select coalesce(array_agg(value order by ordinality), array[]::text[])
        into target_buff_slugs
        from jsonb_array_elements_text(coalesce(skill_row -> 'buff_slugs', '[]'::jsonb))
            with ordinality as item(value, ordinality);

        select coalesce(array_agg(value order by ordinality), array[]::text[])
        into target_debuff_slugs
        from jsonb_array_elements_text(coalesce(skill_row -> 'debuff_slugs', '[]'::jsonb))
            with ordinality as item(value, ordinality);

        if exists (
            select 1
            from unnest(target_buff_slugs || target_debuff_slugs) as slug(value)
            where value !~ '^[a-z0-9_]+$' or value like 'gamekee\_%' escape '\'
        ) then
            raise exception 'Skill payload contains a non-official status-effect key: %:%',
                skill_row ->> 'hero_code', skill_row ->> 'slot' using errcode = '22023';
        end if;

        update public.hero_skills
        set buff_slugs = target_buff_slugs,
            debuff_slugs = target_debuff_slugs,
            updated_at = now()
        where hero_code = skill_row ->> 'hero_code'
          and slot = (skill_row ->> 'slot')::integer;
        get diagnostics affected = row_count;
        if affected <> 1 then
            raise exception 'Expected one hero skill for %:%, updated %',
                skill_row ->> 'hero_code', skill_row ->> 'slot', affected using errcode = 'P0001';
        end if;
        skill_count := skill_count + 1;
    end loop;

    if exists (
        select 1
        from public.hero_skills as skill,
        lateral unnest(skill.buff_slugs || skill.debuff_slugs) as slug(value)
        where slug.value = any(coalesce(p_obsolete_slugs, array[]::text[]))
    ) then
        raise exception 'Obsolete status-effect keys are still referenced by hero skills'
            using errcode = '23503';
    end if;

    delete from public.status_effect_catalog
    where slug = any(coalesce(p_obsolete_slugs, array[]::text[]));

    if exists (
        select 1
        from public.status_effect_catalog
        where slug like 'gamekee\_%' escape '\'
           or slug ~ '-'
           or (icon_url is not null and icon_url !~ ('/' || slug || '\.png$'))
    ) then
        raise exception 'Status-effect catalog still contains legacy keys or icon URLs'
            using errcode = '23514';
    end if;
    if exists (
        select 1
        from public.hero_skills as skill,
        lateral unnest(skill.buff_slugs || skill.debuff_slugs) as slug(value)
        left join public.status_effect_catalog as effect on effect.slug = slug.value
        where slug.value like 'gamekee\_%' escape '\'
           or slug.value ~ '-'
           or effect.slug is null
    ) then
        raise exception 'Hero skills still contain legacy or dangling status-effect keys'
            using errcode = '23503';
    end if;

    return jsonb_build_object(
        'catalog_rows', catalog_count,
        'skill_rows', skill_count,
        'obsolete_rows', cardinality(coalesce(p_obsolete_slugs, array[]::text[]))
    );
end;
$$;

revoke all on function public.replace_status_effect_catalog(jsonb, jsonb, text[])
    from public, anon, authenticated;
grant execute on function public.replace_status_effect_catalog(jsonb, jsonb, text[])
    to service_role;
