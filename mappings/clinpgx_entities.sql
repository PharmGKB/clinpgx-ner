select
    ss.name,
    case when p.name in ('SNV', 'Haplotype', 'GeneCopyNumberVariation', 'StarAllele', 'HlaAllele') then 'Allele' else p.name end type,
    s.id,
    case when s.name=ss.name then 'preferred' else 'alt' end nametype
from
    summaries s
        join summarysearch ss on s.id = ss.id and s.objecttype = ss.objecttype
        join pgkbcomm.pharmgkbobjecttypes p on s.objecttype = p.pharmgkbobjtypeid
where
    p.name in ('Chemical', 'Phenotype', 'Gene', 'SNV', 'Haplotype', 'StarAllele', 'HlaAllele', 'GeneCopyNumberVariation')
  and ss.name != s.id
  and s.hasdata = true
order by p.name, s.name, ss.name;