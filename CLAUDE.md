# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

ClinPGx NER is a Java library that performs Named Entity Recognition on text to find mentions of pharmacogenomic entities (genes, drugs, diseases, variants) using Stanford CoreNLP's RegexNER annotator. Entities are matched against static mapping files bundled in the library.

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

Requires Java 21. JVM is configured with `-Xmx4g` for Gradle-executed tasks (Stanford NLP models are large).

## Architecture

**NerPipeline** is the core class. It wraps a Stanford CoreNLP pipeline configured with `regexner` and `entitymentions` annotators. There are two pipeline types:
- `Type.GENE` - case-sensitive matching against `clinpgx_mapping_gene.txt` (tab-delimited: pattern, NER type, PA accession ID)
- `Type.OTHER` - case-insensitive matching against `clinpgx_mapping_other.txt` (drugs, plus regex patterns for variants like `rs[0-9]+`)

`NerPipeline.runAll(text)` is the main entry point for consumers - it runs both pipelines and merges results.

**DocumentEntity** is the result record containing: matched text, character offsets (begin/end), accession ID (PharmGKB PA ID or dbSNP), and entity type.

## Entity Mapping Files

Located in `src/main/resources/`:
- `clinpgx_mapping_gene.txt` - gene names with PharmGKB PA IDs
- `clinpgx_mapping_other.txt` - drugs and other entities with PA IDs
- `clinpgx_mappings.sql` - the SQL query used to generate the mapping files from PharmGKB

The mapping files use Stanford RegexNER format with header: `pattern, ner, normalized, overwrite, priority, group`.

## Key Behaviors

- Gene matching is case-sensitive; all other entity matching is case-insensitive
- Tokenizer is configured with `strictTreebank3=false,untokenizable=noneKeep,ptb3Escaping=false` to keep technical strings like `CYP2C9` and `rs12345` as single tokens
- Tests use JUnit 5 with `@BeforeAll` to initialize pipelines once (pipeline construction is expensive)
