package org.clinpgx;

import edu.stanford.nlp.ling.CoreAnnotations;
import edu.stanford.nlp.pipeline.CoreDocument;
import edu.stanford.nlp.pipeline.CoreEntityMention;
import edu.stanford.nlp.pipeline.StanfordCoreNLP;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;

public class NerPipeline {
    public enum Type {GENE, OTHER}

    private final static Map<Type, String> typeCatalogMap = Map.of(
            Type.GENE,  "clinpgx_mapping_gene.txt",
            Type.OTHER, "clinpgx_mapping_other.txt");

    private final static Map<Type, String> caseSensitivityMap = Map.of(
            Type.GENE, "false",
            Type.OTHER, "true");

    // Use these flags to keep technical strings like CYP2C9 or rs12345 together.
    // MappingBuilder tokenizes entity names with the same options so patterns line up with tokenized text.
    static final String TOKENIZE_OPTIONS = "strictTreebank3=false,untokenizable=noneKeep,ptb3Escaping=false";

    private final StanfordCoreNLP pipeline;

    public NerPipeline(Type type) {
        this(type, typeCatalogMap.get(type));
    }

    NerPipeline(Type type, String mapping) {
        // 1. Set up the pipeline properties
        Properties props = new Properties();
        props.setProperty("annotators", "tokenize, regexner, entitymentions");
        props.setProperty("regexner.mapping", mapping);
        props.setProperty("regexner.ignorecase", caseSensitivityMap.get(type));
        props.setProperty("regexner.backgroundSymbol", "O");
        props.setProperty("regexner.mapping.header", "pattern,ner,normalized,overwrite,priority,group");
        props.setProperty("tokenize.options", TOKENIZE_OPTIONS);

        // 2. Build the pipeline
        pipeline = new StanfordCoreNLP(props);
    }

    public synchronized List<DocumentEntity> run(String text) {
        List<DocumentEntity> documentEntities = new ArrayList<>();

        if (text != null) {
            // 3. Process your text
            CoreDocument doc = pipeline.processToCoreDocument(text);

            // 4. Extract results
            if (doc.entityMentions() != null) {
                for (CoreEntityMention mention : doc.entityMentions()) {
                    documentEntities.add(
                            new DocumentEntity(
                                    mention.text(),
                                    mention.coreMap().get(CoreAnnotations.CharacterOffsetBeginAnnotation.class),
                                    mention.coreMap().get(CoreAnnotations.CharacterOffsetEndAnnotation.class),
                                    mention.coreMap().get(CoreAnnotations.NormalizedNamedEntityTagAnnotation.class),
                                    mention.entityType()
                            )
                    );
                }
            }
        }

        return documentEntities;
    }

    public static List<DocumentEntity> runAll(String text) {
        List<DocumentEntity> documentEntities = new ArrayList<>();

        documentEntities.addAll(new NerPipeline(Type.GENE).run(text));
        documentEntities.addAll(new NerPipeline(Type.OTHER).run(text));

        return documentEntities;
    }
}
