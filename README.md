# ClinPGx Named Entity Recognition

A Java library and small HTTP service that finds mentions of pharmacogenomic entities in free text, such as genes,
chemicals/drugs, and variants, and links each mention to its ClinPGx (PharmGKB) accession ID.

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

- Matching uses [Stanford CoreNLP](https://stanfordnlp.github.io/CoreNLP/)'s `regexner` annotator. It compares text
  against static entity lists bundled with the library, so it does not use a trained statistical model.
- Gene mentions are **case-sensitive**. All other mentions are **case-insensitive**.
- `begin`/`end` are character offsets into the input string (`end` is exclusive).
- `accessionId` is the ClinPGx PA ID for genes and chemicals. For variants matched by pattern, it is the
  source namespace instead (`dbSNP` for `rs` numbers, `HGVS` for `c.`/`g.`/`p.` notation).

## Requirements

- Java 21. The Gradle toolchain will use or provision it.
- About 4 GB of heap, because the CoreNLP libraries are large. Gradle tasks already pass `-Xmx4g`.

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
java -Xmx4g -jar build/libs/clinpgx-ner-tool.jar
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

The entities come from tab-delimited files in `src/main/resources/` in Stanford RegexNER format
(`pattern<TAB>type<TAB>id`):

| File                        | Pipeline           | Contents                                       |
|-----------------------------|--------------------|------------------------------------------------|
| `clinpgx_mapping_gene.txt`  | `GENE` (case-sensitive) | Gene symbols with PA IDs                  |
| `clinpgx_mapping_other.txt` | `OTHER` (case-insensitive) | Chemicals with PA IDs, plus variant regexes (`rs[0-9]+`, `[cgp]\.[0-9]+[A-Z]>[A-Z]`) |

Each file is generated from the ClinPGx database by its matching `.sql` file (`clinpgx_mapping_gene.sql`,
`clinpgx_mapping_other.sql`). To refresh the entity lists, run those queries, export the results as tab-delimited text
without a header row, replace the `.txt` files, and rebuild. Patterns are whitespace-separated token sequences.
Literal parentheses and periods are escaped, and names that contain parentheses are left out.

You can add entries or regex patterns by hand by appending lines to the `.txt` files.

### Logging

Logging uses `slf4j-simple`, configured in `src/main/resources/simplelogger.properties`. The default level is `debug`.
Set `org.slf4j.simpleLogger.defaultLogLevel` to `info` or `warn` for quieter output. You can also override it at
runtime with `-Dorg.slf4j.simpleLogger.defaultLogLevel=info`.

### Server port and memory

- Set the HTTP port with the `PORT` environment variable. It defaults to `7001`, and a value that isn't an integer stops
  startup with an error:

  ```bash
  PORT=8080 ./gradlew run
  PORT=8080 java -Xmx4g -jar build/libs/clinpgx-ner-tool.jar
  ```

- The heap size for Gradle-launched tasks is set in `build.gradle` (`tasks.withType(JavaExec)`). When you run the fat
  JAR directly, pass `-Xmx` yourself.
