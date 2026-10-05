package org.clinpgx;

import edu.stanford.nlp.ling.CoreLabel;
import edu.stanford.nlp.pipeline.CoreDocument;
import edu.stanford.nlp.pipeline.CoreSentence;
import edu.stanford.nlp.pipeline.StanfordCoreNLP;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Pattern;

/**
 * Finds entity mentions by tokenizing text with CoreNLP and looking up token sequences in a dictionary. The OTHER
 * pipeline also matches the hand-maintained variant patterns. At each position the longest match wins.
 */
public class NerPipeline {
    public enum Type {GENE, OTHER}

    private final static Map<Type, String> typeCatalogMap = Map.of(
            Type.GENE,  "clinpgx_mapping_gene.txt",
            Type.OTHER, "clinpgx_mapping_other.txt");

    // Regex patterns for variants (rs IDs, HGVS), matched by the OTHER pipeline only
    static final String VARIANT_PATTERNS = "clinpgx_variant_patterns.txt";

    // Use these flags to keep technical strings like CYP2C9 or rs12345 together.
    // MappingBuilder tokenizes entity names with the same options so dictionary entries line up with tokenized text.
    static final String TOKENIZE_OPTIONS = "strictTreebank3=false,untokenizable=noneKeep,ptb3Escaping=false";

    private record Mapping(String type, String id) {}

    // One regex per token, e.g. [cgp]\. [0-9]+[A-Z] > [A-Z]\.? for c.123A>G
    private record VariantPattern(List<Pattern> tokens, Mapping mapping) {}

    private final StanfordCoreNLP tokenizer;
    private final boolean ignoreCase;
    private final TokenTrie<Mapping> dictionary = new TokenTrie<>();
    private final List<VariantPattern> variantPatterns = new ArrayList<>();

    public NerPipeline(Type type) {
        this(type, typeCatalogMap.get(type));
    }

    /**
     * @param mapping dictionary file, as a file path or a classpath resource name
     */
    NerPipeline(Type type, String mapping) {
        ignoreCase = type == Type.OTHER;

        Properties props = new Properties();
        props.setProperty("annotators", "tokenize");
        props.setProperty("tokenize.options", TOKENIZE_OPTIONS);
        tokenizer = new StanfordCoreNLP(props);

        // entries are space-separated tokens; tokens never contain ASCII spaces
        for (String[] fields : readMapping(mapping)) {
            List<String> tokens = Arrays.stream(fields[0].split(" ")).map(this::normalize).toList();
            dictionary.put(tokens, new Mapping(fields[1], fields[2]));
        }

        if (type == Type.OTHER) {
            int flags = ignoreCase ? Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE : 0;
            for (String[] fields : readMapping(VARIANT_PATTERNS)) {
                List<Pattern> tokens = Arrays.stream(fields[0].split("\\s+")).map(p -> Pattern.compile(p, flags)).toList();
                variantPatterns.add(new VariantPattern(tokens, new Mapping(fields[1], fields[2])));
            }
        }
    }

    public synchronized List<DocumentEntity> run(String text) {
        List<DocumentEntity> documentEntities = new ArrayList<>();

        if (text != null) {
            CoreDocument doc = tokenizer.processToCoreDocument(text);

            for (CoreSentence sentence : doc.sentences()) {
                List<CoreLabel> tokens = sentence.tokens();
                List<String> words = tokens.stream().map(CoreLabel::word).toList();
                List<String> normalized = words.stream().map(this::normalize).toList();

                int i = 0;
                while (i < tokens.size()) {
                    TokenTrie.Match<Mapping> match = longestMatch(words, normalized, i);
                    if (match == null) {
                        i++;
                        continue;
                    }
                    int begin = tokens.get(i).beginPosition();
                    int end = tokens.get(i + match.length() - 1).endPosition();
                    documentEntities.add(new DocumentEntity(
                            text.substring(begin, end), begin, end, match.value().id(), match.value().type()));
                    i += match.length();
                }
            }
        }

        return documentEntities;
    }

    private TokenTrie.Match<Mapping> longestMatch(List<String> words, List<String> normalized, int start) {
        TokenTrie.Match<Mapping> best = dictionary.longestMatch(normalized, start);
        for (VariantPattern pattern : variantPatterns) {
            int length = pattern.tokens().size();
            if (start + length > words.size() || (best != null && best.length() >= length)) {
                continue;
            }
            boolean matches = true;
            for (int j = 0; j < length && matches; j++) {
                matches = pattern.tokens().get(j).matcher(words.get(start + j)).matches();
            }
            if (matches) {
                best = new TokenTrie.Match<>(length, pattern.mapping());
            }
        }
        return best;
    }

    private String normalize(String token) {
        return ignoreCase ? token.toLowerCase(Locale.ROOT) : token;
    }

    /**
     * Reads a {@code pattern<TAB>type<TAB>id} file from a path or the classpath, skipping blank and {@code #} lines.
     */
    private static List<String[]> readMapping(String mapping) {
        try (BufferedReader reader = open(mapping)) {
            List<String[]> rows = new ArrayList<>();
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank() || line.startsWith("#")) {
                    continue;
                }
                String[] fields = line.split("\t");
                if (fields.length < 3) {
                    throw new IllegalArgumentException(mapping + ": expected pattern<TAB>type<TAB>id, got: " + line);
                }
                rows.add(fields);
            }
            return rows;
        } catch (IOException e) {
            throw new UncheckedIOException("Error reading " + mapping, e);
        }
    }

    private static BufferedReader open(String mapping) throws IOException {
        Path path = Path.of(mapping);
        if (Files.isRegularFile(path)) {
            return Files.newBufferedReader(path, StandardCharsets.UTF_8);
        }
        InputStream in = NerPipeline.class.getClassLoader().getResourceAsStream(mapping);
        if (in == null) {
            throw new IOException("Mapping not found as a file or classpath resource: " + mapping);
        }
        return new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
    }

    public static List<DocumentEntity> runAll(String text) {
        List<DocumentEntity> documentEntities = new ArrayList<>();

        documentEntities.addAll(new NerPipeline(Type.GENE).run(text));
        documentEntities.addAll(new NerPipeline(Type.OTHER).run(text));

        return documentEntities;
    }
}
