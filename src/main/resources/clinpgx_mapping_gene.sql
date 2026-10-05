(
    with types as (
        select pharmgkbobjtypeid as type, name as typename from pharmgkbobjecttypes where name='Gene'
    )
    select
        regexp_replace(s.name, '([\(\)\.])', ' \\\\\1 ', 'g') as pattern,
        t.typename as type,
        s.id
    from summaries s join types t on s.objecttype=t.type
    where s.name !~ '[\)\(]'
)
    order by 2,1;