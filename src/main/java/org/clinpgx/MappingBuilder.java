package org.clinpgx;

import edu.stanford.nlp.ling.CoreLabel;
import edu.stanford.nlp.pipeline.StanfordCoreNLP;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Builds the RegexNER mapping files from a tab-delimited dump of entities
 * ({@code name<TAB>type<TAB>id[<TAB>preferred|alt]}).
 * <p>
 * Each name is run through the same tokenizer {@link NerPipeline} uses, so a pattern like {@code HLA-B} becomes
 * {@code HLA - B} and lines up with how the name is tokenized in text. Genes, alleles, and short all-caps abbreviations
 * go to the case-sensitive gene file; everything else goes to the case-insensitive other file, followed by the
 * hand-maintained variant patterns.
 * <p>
 * Usage: {@code MappingBuilder <entities.tsv> <variant_patterns.txt> <output dir>}
 */
public class MappingBuilder {
    public static final String GENE_FILE = "clinpgx_mapping_gene.txt";
    public static final String OTHER_FILE = "clinpgx_mapping_other.txt";

    // Types matched case-sensitively in the gene pipeline; all other types are matched case-insensitively
    private static final Set<String> GENE_FILE_TYPES = Set.of("Gene", "Haplotype", "Allele");

    // Short all-caps names of other types (THE, NO, 5-FU) are abbreviations; matching them case-insensitively would
    // tag ordinary words like "the" and "no", so they go to the case-sensitive gene file instead
    private static final Pattern ABBREVIATION = Pattern.compile("[A-Z0-9-]{2,6}");

    // Characters TokensRegexNER treats as regex syntax. Tokens without them are compared as plain strings.
    private static final String REGEX_CHARS = "[]?.\\^$()*+{}|";

    // Reasons an entity is left out of the mapping files entirely
    public static final String EXCLUDED_HGVS = "HGVS name with reference sequence";
    public static final String EXCLUDED_XREF = "cross-reference ID";
    public static final String EXCLUDED_SINGLE_CHARACTER = "single character";

    // Full HGVS expressions anchored on a reference sequence, e.g. NM_000771.4(CYP2C9):c.458T>C or
    // NC_000001.11:g.101409030T>G. These rarely appear verbatim in text and are slow for RegexNER to match.
    // Bare forms like c.1236G>A are kept.
    private static final Pattern HGVS_WITH_REFERENCE =
            Pattern.compile("^(?:N[CGMPRTW]|X[MPR]|ENS[GPT]|LRG)_?\\d+(?:\\.\\d+)?(?:\\([^)]*\\))?:[cgmnopr]\\.");

    // Database cross-references from the search index, e.g. refSeqProtein:NP_000762 or HGNC:2623
    private static final Pattern CROSS_REFERENCE =
            Pattern.compile("^(?:refSeq(?:Dna|Rna|Protein)|HGNC|ATC|RxNorm|MeSH|SnoMedCT):\\S+$");

    public record Entity(String name, String type, String id, boolean preferred) {
        public Entity(String name, String type, String id) {
            this(name, type, id, true);
        }
    }

    public record Result(List<String> geneLines, List<String> otherLines, List<String> warnings,
                         Map<String, Integer> excluded) {}

    private final StanfordCoreNLP tokenizer;

    public MappingBuilder() {
        Properties props = new Properties();
        props.setProperty("annotators", "tokenize");
        props.setProperty("tokenize.options", NerPipeline.TOKENIZE_OPTIONS);
        tokenizer = new StanfordCoreNLP(props);
    }

    /**
     * Converts an entity name to a RegexNER pattern: one escaped regex per token, separated by spaces.
     */
    public String toPattern(String name) {
        return tokenizer.processToCoreDocument(name.strip()).tokens().stream()
                .map(CoreLabel::word)
                .map(MappingBuilder::escape)
                .collect(Collectors.joining(" "));
    }

    private static String escape(String token) {
        StringBuilder sb = new StringBuilder(token.length());
        for (char c : token.toCharArray()) {
            if (REGEX_CHARS.indexOf(c) >= 0) {
                sb.append('\\');
            }
            sb.append(c);
        }
        return sb.toString();
    }

    /**
     * Splits entities into gene and other mapping lines. When a name maps to more than one type/ID within a file, the
     * one entity that lists it as its preferred name wins; if there isn't exactly one, the name is dropped, since
     * RegexNER would silently keep whichever came first.
     */
    public Result build(List<Entity> entities) {
        // key -> distinct (type, id) candidates, in input order
        Map<String, Map<String, Candidate>> gene = new LinkedHashMap<>();
        Map<String, Map<String, Candidate>> other = new LinkedHashMap<>();
        Map<String, Integer> excluded = new LinkedHashMap<>();

        for (Entity entity : entities) {
            String reason = exclusionReason(entity.name());
            if (reason != null) {
                excluded.merge(reason, 1, Integer::sum);
                continue;
            }
            String pattern = toPattern(entity.name());
            if (pattern.isEmpty()) {
                continue;
            }
            boolean isGeneFile = GENE_FILE_TYPES.contains(entity.type()) || isAbbreviation(entity.name());
            // the other file is matched case-insensitively, so case variants collide there
            String key = isGeneFile ? pattern : pattern.toLowerCase(Locale.ROOT);
            (isGeneFile ? gene : other)
                    .computeIfAbsent(key, k -> new LinkedHashMap<>())
                    .merge(entity.type() + "\t" + entity.id(),
                            new Candidate(pattern + "\t" + entity.type() + "\t" + entity.id(), entity.preferred()),
                            // the same entity can list a name as both preferred and alt
                            (a, b) -> a.preferred() ? a : b);
        }

        List<String> warnings = new ArrayList<>();
        List<String> geneLines = resolve(gene, GENE_FILE, warnings);
        List<String> otherLines = resolve(other, OTHER_FILE, warnings);

        // the other pipeline ignores case, so it will also match these names wherever the gene pipeline does
        Set<String> geneKeysLower = gene.keySet().stream()
                .map(k -> k.toLowerCase(Locale.ROOT))
                .collect(Collectors.toSet());
        other.keySet().stream()
                .filter(geneKeysLower::contains)
                .forEach(k -> warnings.add("In both files (overlapping matches): " + k));

        return new Result(geneLines, otherLines, warnings, excluded);
    }

    private record Candidate(String line, boolean preferred) {}

    private static boolean isAbbreviation(String name) {
        return ABBREVIATION.matcher(name).matches() && name.chars().anyMatch(Character::isLetter);
    }

    private static String exclusionReason(String name) {
        if (name.length() == 1) {
            return EXCLUDED_SINGLE_CHARACTER;
        }
        if (HGVS_WITH_REFERENCE.matcher(name).find()) {
            return EXCLUDED_HGVS;
        }
        if (CROSS_REFERENCE.matcher(name).matches()) {
            return EXCLUDED_XREF;
        }
        return null;
    }

    private static List<String> resolve(Map<String, Map<String, Candidate>> byKey, String file, List<String> warnings) {
        List<String> lines = new ArrayList<>();
        byKey.forEach((key, candidates) -> {
            List<Candidate> preferred = candidates.values().stream().filter(Candidate::preferred).toList();
            if (candidates.size() == 1) {
                lines.add(candidates.values().iterator().next().line());
            } else if (preferred.size() == 1) {
                lines.add(preferred.getFirst().line());
            } else {
                warnings.add("Ambiguous in " + file + " (dropped): " + key + " -> " + String.join(", ", candidates.keySet()));
            }
        });
        // sort by type, then pattern
        lines.sort(Comparator.comparing((String l) -> l.split("\t")[1])
                .thenComparing(l -> l.split("\t")[0], String.CASE_INSENSITIVE_ORDER)
                .thenComparing(Comparator.naturalOrder()));
        return lines;
    }

    /**
     * Reads a {@code name<TAB>type<TAB>id[<TAB>preferred|alt]} file, skipping blank lines and an optional
     * {@code name} header row. Rows without the fourth column are treated as preferred.
     */
    public static List<Entity> readEntities(Path input) throws IOException {
        List<Entity> entities = new ArrayList<>();
        List<String> lines = Files.readAllLines(input);
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.isBlank() || (i == 0 && line.toLowerCase(Locale.ROOT).startsWith("name\t"))) {
                continue;
            }
            String[] fields = line.split("\t", -1);
            boolean valid = (fields.length == 3 || fields.length == 4)
                    && !fields[0].isBlank() && !fields[1].isBlank() && !fields[2].isBlank()
                    && (fields.length == 3 || fields[3].strip().equals("preferred") || fields[3].strip().equals("alt"));
            if (!valid) {
                throw new IllegalArgumentException(
                        input + " line " + (i + 1) + ": expected name<TAB>type<TAB>id[<TAB>preferred|alt], got: " + line);
            }
            boolean preferred = fields.length == 3 || fields[3].strip().equals("preferred");
            entities.add(new Entity(fields[0].strip(), fields[1].strip(), fields[2].strip(), preferred));
        }
        return entities;
    }

    /**
     * Writes the gene and other mapping files to {@code outputDir}, appending the variant patterns to the other file.
     */
    public static void writeMappings(Result result, Path variantPatterns, Path outputDir) throws IOException {
        List<String> variants = Files.readAllLines(variantPatterns).stream()
                .filter(l -> !l.isBlank() && !l.startsWith("#"))
                .toList();
        List<String> otherLines = new ArrayList<>(result.otherLines());
        otherLines.addAll(variants);

        Files.createDirectories(outputDir);
        Files.write(outputDir.resolve(GENE_FILE), result.geneLines());
        Files.write(outputDir.resolve(OTHER_FILE), otherLines);
    }

    public static void main(String[] args) throws IOException {
        if (args.length != 3) {
            System.err.println("Usage: MappingBuilder <entities.tsv> <variant_patterns.txt> <output dir>");
            System.exit(1);
        }
        Path input = Path.of(args[0]);
        Path variants = Path.of(args[1]);
        Path outputDir = Path.of(args[2]);

        List<Entity> entities = readEntities(input);
        Result result = new MappingBuilder().build(entities);
        writeMappings(result, variants, outputDir);

        result.warnings().forEach(w -> System.out.println("WARN " + w));
        System.out.printf("Read %d entities from %s%n", entities.size(), input);
        result.excluded().forEach((reason, count) -> System.out.printf("Excluded %d (%s)%n", count, reason));
        System.out.printf("Wrote %d lines to %s%n", result.geneLines().size(), outputDir.resolve(GENE_FILE));
        System.out.printf("Wrote %d lines (plus variant patterns) to %s%n", result.otherLines().size(), outputDir.resolve(OTHER_FILE));
        System.out.printf("%d warnings%n", result.warnings().size());
    }
}
