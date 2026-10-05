# ClinPGx Named Entity Recognition

A Java library and small HTTP service that finds mentions of pharmacogenomic entities in free text, such as genes,
alleles, chemicals/drugs, diseases, and variants, and links each mention to its ClinPGx (PharmGKB) accession ID.

## Purpose

Given a string like:

> CYP2C9 affects warfarin dosing; see rs1799853.

it returns every recognized entity with its matched text, character offsets, type, and identifier:

```json
[
  {"text": "CYP2C9",    "begin": 0,  "end": 6,  "accessionId": "PA126",    "type": "Gene"},
  {"text": "warfarin",  "begin": 15, "end": 23, "accessionId": "PA451906", "type": "Chemical"},
  {"text": "rs1799853", "begin": 36, "end": 45, "accessionId": "dbSNP",    "type": "Variant"}
]
```

How it works:

- Text is tokenized with [Stanford CoreNLP](https://stanfordnlp.github.io/CoreNLP/)'s tokenizer, and token sequences are
  looked up in dictionaries bundled with the library (no trained statistical model). The lookup takes about the same
  time per token however large the dictionaries are. Variants are matched by a few hand-written regex patterns.
- At each position the longest match wins, so `CYP2C19*2` is one `Allele`, not the gene `CYP2C19` plus extra text.
- Gene mentions are **case-sensitive**. All other mentions are **case-insensitive**.
- `begin`/`end` are character offsets into the input string (`end` is exclusive).
- `accessionId` is the ClinPGx PA ID for genes and chemicals. For variants matched by pattern, it is the
  source namespace instead (`dbSNP` for `rs` numbers, `HGVS` for `c.`/`g.`/`p.` notation).

## Requirements

- Java 21. The Gradle toolchain will use or provision it.
- A small heap. Both pipelines together retain about 50 MB after loading, and a 256 MB heap (`-Xmx256m`) is plenty for
  the API server. In testing, a single 533 KB request needed about 128 MB. Gradle tasks pass `-Xmx512m`.
- Disk: the fat JAR is about 30 MB. Only the core CoreNLP library is used, for its tokenizer; the separate CoreNLP
  models artifact (about 470 MB) isn't needed.

## Running

### Build and test

```bash
./gradlew build          # compile and run tests
./gradlew test           # tests only
```

### HTTP API server

Start the server (port `7001` by default; see [Configuration](#server-port-and-memory)):

```bash
./gradlew run
```

Or build a self-contained fat JAR and run it directly:

```bash
./gradlew shadowJar
java -Xmx256m -jar build/libs/clinpgx-ner-tool.jar
```

Send text as the raw request body to `POST /ner`:

```bash
curl -X POST http://localhost:7001/ner \
  -d 'this sentence mentions CYP2C9, the drug clopidogrel, and variant rs12345.'
```

The response is a JSON array of entities, shown above. A sample request for IntelliJ's HTTP client is in
`src/main/resources/clinpgx-ner.http`.

### As a library

```java
// One-off: builds both pipelines, runs them, and merges the results
List<DocumentEntity> entities = NerPipeline.runAll(text);

// Repeated use: build pipelines once and reuse them (construction is expensive)
NerPipeline genes = new NerPipeline(NerPipeline.Type.GENE);
NerPipeline other = new NerPipeline(NerPipeline.Type.OTHER);
List<DocumentEntity> results = new ArrayList<>(genes.run(text));
results.addAll(other.run(text));
```

`NerPipeline.runAll` creates new pipelines on every call. For anything beyond a one-off, build the pipelines once and
reuse them, as `ApiServer` does. `run` is `synchronized`, so one pipeline instance can be shared across threads.

## Configuration

### Entity lists

The pipelines read these files from `src/main/resources/`:

| File                           | Pipeline                   | Contents                                                       |
|--------------------------------|----------------------------|----------------------------------------------------------------|
| `clinpgx_mapping_gene.txt`     | `GENE` (case-sensitive)    | Genes, alleles/haplotypes, and short all-caps abbreviations    |
| `clinpgx_mapping_other.txt`    | `OTHER` (case-insensitive) | All other entity names                                         |
| `clinpgx_variant_patterns.txt` | `OTHER` (case-insensitive) | Hand-maintained variant regexes (`rs[0-9]+`, HGVS like `c.123A>G`) |

Each line is `tokens<TAB>type<TAB>id`. In the two dictionary files, `tokens` is the name as the tokenizer splits it,
separated by spaces (`HLA - B`, `CYP2C19 * 2`).

**The dictionary files are generated; don't edit them by hand.** `MappingBuilder` builds them from
`mappings/clinpgx_entities.tsv`, a dump of ClinPGx entity names produced by `mappings/clinpgx_entities.sql`. The dump has
four tab-separated columns: `name`, `type`, `id`, and `preferred` or `alt`. Use raw names, with no escaping or
spacing. An optional `name` header row is allowed, and rows without the fourth column count as preferred.

To regenerate:

```bash
./gradlew buildMappings                                # reads mappings/clinpgx_entities.tsv
./gradlew buildMappings -Pentities=/path/to/dump.tsv   # or a dump stored elsewhere
```

The builder:

- **Tokenizes each name with the same tokenizer as `NerPipeline`.** Lookup works token by token, so `HLA-B` is stored
  as `HLA - B`, matching how it's split in text.
- **Excludes names that would never match or would match too much:**
  - full HGVS expressions on a reference sequence (`NM_000771.4(CYP2C9):c.458T>C`, `NC_000001.11:g.…`); short forms
    like `c.1236G>A` are kept
  - database cross-references (`refSeqProtein:…`, `HGNC:…`, `RxNorm:…`, `ATC:…`, `MeSH:…`, `SnoMedCT:…`)
  - bare codes: all-digit names (SNOMED, PubChem and similar IDs), which would match years and counts like "2019",
    and MeSH IDs like `D015746`
  - single-character names
  - gene alt names of 2–3 letters (`CI`, `SD`, `MI`, `ER`…), which are mostly old symbols that collide with common
    abbreviations in papers. Preferred gene symbols (`MET`, `TNF`) and alphanumeric aliases (`P53`) are kept.

  It also strips source qualifiers such as `[D]` from names like `[D]Abdominal pain`.
- **Routes names by type and shape.** `Gene`, `Allele` and `Haplotype` go to the case-sensitive gene file. So do
  short all-caps names of other types, such as `THE` or `NO`, so that they don't match the words "the" and "no".
  Everything else goes to the case-insensitive other file.
- **Resolves ambiguous names.** If a name maps to more than one entity in the same file, the entity that lists it as
  its preferred name wins. If no entity prefers it, and it's an alt name for one phenotype and otherwise only genes
  (`COPD`, `CML`), the phenotype wins. Otherwise the name is dropped with a warning. The builder also warns about names that
  appear in both files, because both pipelines will then match them.
- **Reports counts per type** and warns if an expected type (`Allele`, `Chemical`, `Gene`, `Phenotype`) has no rows.
  A missing type usually means the dump was exported before the SQL was updated.

To change the variant patterns, edit `clinpgx_variant_patterns.txt` directly. Each space-separated part is a regex
for one token, so patterns must follow the tokenizer's splits. For example, `c.123A>G` tokenizes as `c.` `123A` `>`
`G`. At the end of a sentence, the tokenizer keeps a period on a trailing single capital letter (`G.`).

### Logging

Logging uses `slf4j-simple`, configured in `src/main/resources/simplelogger.properties`. The default level is `debug`.
Set `org.slf4j.simpleLogger.defaultLogLevel` to `info` or `warn` for quieter output. You can also override it at
runtime with `-Dorg.slf4j.simpleLogger.defaultLogLevel=info`.

### Server port and memory

- Set the HTTP port with the `PORT` environment variable. It defaults to `7001`, and a value that isn't an integer stops
  startup with an error:

  ```bash
  PORT=8080 ./gradlew run
  PORT=8080 java -Xmx256m -jar build/libs/clinpgx-ner-tool.jar
  ```

- The heap size for Gradle-launched tasks is set in `build.gradle` (`tasks.withType(JavaExec)`). When you run the fat
  JAR directly, pass `-Xmx` yourself. Memory use grows with the size of each request body and the number of
  concurrent requests, not with the dictionaries. Raise the heap if you send very large documents.
