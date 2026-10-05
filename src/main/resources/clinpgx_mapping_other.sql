select 'rs[0-9]+' as pattern, 'Variant' as type, 'dbSNP' as id
union all
select '[cgp]\.[0-9]+[A-Z]>[A-Z]', 'Variant', 'HGVS'
union all (
    with types as (
        select pharmgkbobjtypeid as type, name as typename from pharmgkbobjecttypes where name='Chemical'
    )
    select
        regexp_replace(s.name, '([\(\)\.])', ' \\\\\1 ', 'g') as pattern,
        t.typename as type,
        s.id
    from summaries s join types t on s.objecttype=t.type
    where s.name !~ '[\)\(]'
)
order by 2,1;