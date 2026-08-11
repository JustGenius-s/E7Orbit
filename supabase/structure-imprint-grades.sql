-- Backfill structured imprint grade fields (stat/amount/percent) in
-- hero_catalog.memory_imprint, so clients can read them directly instead of
-- parsing the English display text.
--
-- Safe to run multiple times: grades that already carry a structured stat are
-- left untouched. Run in the Supabase SQL Editor.

begin;

create or replace function public.structure_imprint_grades(section jsonb)
returns jsonb language plpgsql immutable as $$
declare
    upgraded jsonb;
begin
    if section is null or not (section ? 'grades') then
        return section;
    end if;

    select jsonb_agg(
        case
            when grade ? 'stat' then grade
            when parsed.stat is null then grade
            else grade || jsonb_build_object(
                'stat', parsed.stat,
                'amount', parsed.amount,
                'percent', parsed.percent
            )
        end
        order by ord
    )
    into upgraded
    from jsonb_array_elements(section -> 'grades') with ordinality as t(grade, ord)
    cross join lateral (
        select
            case lower(trim(m[1]))
                when 'attack' then 'attack'
                when 'health' then 'health'
                when 'defense' then 'defense'
                when 'speed' then 'speed'
                when 'critical hit chance' then 'critical_chance'
                when 'critical hit damage' then 'critical_damage'
                when 'effectiveness' then 'effectiveness'
                when 'effect resistance' then 'effect_resistance'
            end as stat,
            m[2]::double precision as amount,
            (m[3] = '%') as percent
        from regexp_match(
            trim(grade ->> 'value'),
            '^([A-Za-z ]+?)\s*\+?\s*([0-9]+(?:\.[0-9]+)?)\s*(%?)$'
        ) as m
    ) as parsed;

    return jsonb_set(section, '{grades}', coalesce(upgraded, '[]'::jsonb));
end;
$$;

update public.hero_catalog
set memory_imprint = jsonb_set(
    jsonb_set(
        memory_imprint,
        '{release}',
        public.structure_imprint_grades(memory_imprint -> 'release'),
        false
    ),
    '{concentration}',
    public.structure_imprint_grades(memory_imprint -> 'concentration'),
    false
)
where memory_imprint <> '{}'::jsonb
  and memory_imprint is not null;

-- Clean up the helper; it is only needed for this backfill.
drop function public.structure_imprint_grades(jsonb);

commit;
