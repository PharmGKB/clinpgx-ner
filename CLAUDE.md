# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

ClinPGx NER is a Java library that performs Named Entity Recognition on text to find mentions of pharmacogenomic entities (genes, drugs, diseases, variants) by tokenizing with Stanford CoreNLP and looking up token sequences in static dictionary files bundled in the library.

## Build Commands

```bash
./gradlew build          # Build and run tests
./gradlew test           # Run tests only
./gradlew shadowJar      # Build fat JAR (output: build/libs/clinpgx-ner-tool.jar)
./gradlew run            # Run Main class (demo)
```

Run a single test class:
```bash
./gradlew test --tests "org.clinpgx.NerPipelineTest"
```

Run a single test method:
```bash
./gradlew test --tests "org.clinpgx.NerPipelineTest.testGenePipelineRecognizesKnownGene"
```

Requires Java 21. Only the core `stanford-corenlp` artifact is used (for its tokenizer); the `models` artifact is deliberately not a dependency. Both pipelines retain ~50 MB of heap; Gradle-executed tasks run with `-Xmx512m`, and the fat JAR is ~30 MB.

## Architecture

**NerPipeline** is the core class. It tokenizes text with a CoreNLP `tokenize`-only pipeline, then scans each sentence for the leftmost-longest match in a **TokenTrie** (dictionary of token sequences). The OTHER pipeline also tries the variant regexes in `clinpgx_variant_patterns.txt` (one regex per token); the longest match at a position wins. There are two pipeline types:
- `Type.GENE` - case-sensitive matching against `clinpgx_mapping_gene.txt` (genes, alleles, short all-caps abbreviations; tab-delimited: tokens, type, PA accession ID)
- `Type.OTHER` - case-insensitive matching against `clinpgx_mapping_other.txt` (all other entity types; tokens are lowercased), plus `clinpgx_variant_patterns.txt` (regexes like `rs[0-9]+`)

`NerPipeline.runAll(text)` is the main entry point for consumers - it runs both pipelines and merges results.

**DocumentEntity** is the result record containing: matched text, character offsets (begin/end), accession ID (PharmGKB PA ID or dbSNP), and entity type.

## Entity Mapping Files

Located in `src/main/resources/`:
- `clinpgx_mapping_gene.txt` - genes, alleles, and short all-caps abbreviations with PharmGKB PA IDs (generated)
- `clinpgx_mapping_other.txt` - all other entities with PA IDs (generated)
- `clinpgx_variant_patterns.txt` - hand-maintained variant regexes, one regex per token

Dictionary lines are `tokens<TAB>type<TAB>id`, where `tokens` are space-separated and unescaped.

The two mapping `.txt` files are generated, not hand-edited: `./gradlew buildMappings` runs `MappingBuilder` over
`mappings/clinpgx_entities.tsv` (`name<TAB>type<TAB>id<TAB>preferred|alt` dump from `mappings/clinpgx_entities.sql`).
`MappingBuilder` tokenizes names with `NerPipeline.TOKENIZE_OPTIONS` — entries must use the same token splits as the
text (e.g. `HLA-B` → `HLA - B`), or they silently never match. It also filters HGVS-on-reference-sequence names,
cross-reference IDs, bare numeric/MeSH codes, single characters, and 2–3 letter gene alt names, strips `[D]`-style qualifier prefixes, and resolves ambiguous names in favor of the preferred name (then: a lone alt Phenotype beats alt Genes). It warns when an expected type (`MappingBuilder.EXPECTED_TYPES`: Allele, Chemical, Gene, Phenotype) has no rows, which usually means the dump is stale.

## Key Behaviors

- Gene matching is case-sensitive; all other entity matching is case-insensitive
- Tokenizer is configured with `strictTreebank3=false,untokenizable=noneKeep,ptb3Escaping=false` to keep technical strings like `CYP2C9` and `rs12345` as single tokens
- Tests use JUnit 5 with `@BeforeAll` to initialize pipelines once (pipeline construction is expensive)
