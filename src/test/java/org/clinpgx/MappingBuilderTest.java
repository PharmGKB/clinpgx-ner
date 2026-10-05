package org.clinpgx;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MappingBuilderTest {

    private static MappingBuilder builder;

    @BeforeAll
    static void setUp() {
        builder = new MappingBuilder();
    }

    @Test
    void testToTokensSplitsHyphens() {
        assertEquals("HLA - B", builder.toTokens("HLA-B"));
    }

    @Test
    void testToTokensSplitsPunctuationWithoutEscaping() {
        assertEquals("CYP2C19 * 2", builder.toTokens("CYP2C19*2"));
        assertEquals("abacavir ( ABC )", builder.toTokens("abacavir (ABC)"));
        assertEquals("St. John 's wort", builder.toTokens("St. John's wort"));
    }

    @Test
    void testToTokensStripsWhitespace() {
        assertEquals("CYP2C9", builder.toTokens("CYP2C9"));
        assertEquals("warfarin", builder.toTokens("  warfarin "));
    }

    @Test
    void testBuildRoutesGenesAndHaplotypesToGeneFile() {
        MappingBuilder.Result result = builder.build(List.of(
                new MappingBuilder.Entity("CYP2C19", "Gene", "PA124"),
                new MappingBuilder.Entity("CYP2C19*2", "Haplotype", "PA165980635"),
                new MappingBuilder.Entity("warfarin", "Chemical", "PA451906"),
                new MappingBuilder.Entity("Diabetes Mellitus", "Phenotype", "PA443890")));

        assertEquals(List.of("CYP2C19\tGene\tPA124", "CYP2C19 * 2\tHaplotype\tPA165980635"), result.geneLines());
        assertEquals(List.of("warfarin\tChemical\tPA451906", "Diabetes Mellitus\tPhenotype\tPA443890"), result.otherLines());
    }

    @Test
    void testBuildDropsExactDuplicatesSilently() {
        MappingBuilder.Result result = builder.build(List.of(
                new MappingBuilder.Entity("warfarin", "Chemical", "PA451906"),
                new MappingBuilder.Entity("warfarin", "Chemical", "PA451906")));

        assertEquals(List.of("warfarin\tChemical\tPA451906"), result.otherLines());
        assertTrue(result.warnings().isEmpty());
    }

    @Test
    void testBuildDropsAmbiguousNamesAndWarns() {
        MappingBuilder.Result result = builder.build(List.of(
                new MappingBuilder.Entity("Aspirin", "Chemical", "PA448497"),
                new MappingBuilder.Entity("aspirin", "Chemical", "PA999999"),
                new MappingBuilder.Entity("warfarin", "Chemical", "PA451906")));

        // the other file is case-insensitive, so "Aspirin" and "aspirin" collide
        assertEquals(List.of("warfarin\tChemical\tPA451906"), result.otherLines());
        assertEquals(1, result.warnings().size());
        assertTrue(result.warnings().getFirst().contains("aspirin"));
    }

    @Test
    void testBuildKeepsCaseVariantsInGeneFile() {
        MappingBuilder.Result result = builder.build(List.of(
                new MappingBuilder.Entity("ABC", "Gene", "PA1"),
                new MappingBuilder.Entity("Abc", "Gene", "PA2")));

        assertEquals(2, result.geneLines().size());
        assertTrue(result.warnings().isEmpty());
    }

    @Test
    void testBuildWarnsWhenNameIsInBothFiles() {
        MappingBuilder.Result result = builder.build(List.of(
                new MappingBuilder.Entity("MET", "Gene", "PA1"),
                new MappingBuilder.Entity("met", "Chemical", "PA2")));

        assertEquals(1, result.geneLines().size());
        assertEquals(1, result.otherLines().size());
        assertEquals(1, result.warnings().size());
    }

    @Test
    void testBuildPrefersPreferredNameWhenAmbiguous() {
        MappingBuilder.Result result = builder.build(List.of(
                new MappingBuilder.Entity("ELN", "Gene", "PA27757", true),
                new MappingBuilder.Entity("ELN", "Gene", "PA166049054", false)));

        assertEquals(List.of("ELN\tGene\tPA27757"), result.geneLines());
        assertTrue(result.warnings().isEmpty());
    }

    @Test
    void testBuildDropsAmbiguousNameWhenNoSinglePreferred() {
        MappingBuilder.Result result = builder.build(List.of(
                new MappingBuilder.Entity("NG_002398", "Gene", "PA1", false),
                new MappingBuilder.Entity("NG_002398", "Gene", "PA2", false)));

        assertTrue(result.geneLines().isEmpty());
        assertEquals(1, result.warnings().size());
    }

    @Test
    void testBuildExcludesHgvsNamesWithReferenceSequence() {
        MappingBuilder.Result result = builder.build(List.of(
                new MappingBuilder.Entity("NM_000771.4(CYP2C9):c.458T>C (p.Val153Ala)", "Allele", "PA1"),
                new MappingBuilder.Entity("NC_000001.11:g.101409030T>G", "Allele", "PA2"),
                new MappingBuilder.Entity("NM_000110.4(DPYD):c.2998G>A (p.Asp1000Asn)", "Allele", "PA3"),
                new MappingBuilder.Entity("c.1236G>A", "Allele", "PA4"),
                new MappingBuilder.Entity("NM_000771", "Gene", "PA126")));

        assertEquals(List.of("c. 1236G > A\tAllele\tPA4", "NM_000771\tGene\tPA126"), result.geneLines());
        assertEquals(3, result.excluded().get(MappingBuilder.EXCLUDED_HGVS));
    }

    @Test
    void testBuildExcludesCrossReferenceIds() {
        MappingBuilder.Result result = builder.build(List.of(
                new MappingBuilder.Entity("refSeqProtein:NP_000762", "Gene", "PA126"),
                new MappingBuilder.Entity("HGNC:2623", "Gene", "PA126"),
                new MappingBuilder.Entity("RxNorm:11289", "Chemical", "PA451906"),
                new MappingBuilder.Entity("NADH:ubiquinone oxidoreductase core subunit S1", "Gene", "PA31411")));

        assertEquals(1, result.geneLines().size());
        assertTrue(result.otherLines().isEmpty());
        assertEquals(3, result.excluded().get(MappingBuilder.EXCLUDED_XREF));
    }

    @Test
    void testReadEntitiesParsesPreferredColumn(@TempDir Path dir) throws IOException {
        Path input = dir.resolve("entities.tsv");
        Files.writeString(input, "CYP2C9\tGene\tPA126\tpreferred\nCYP2C10\tGene\tPA126\talt\n");

        assertEquals(List.of(
                new MappingBuilder.Entity("CYP2C9", "Gene", "PA126", true),
                new MappingBuilder.Entity("CYP2C10", "Gene", "PA126", false)),
                MappingBuilder.readEntities(input));
    }

    @Test
    void testReadEntitiesRejectsUnknownPreferredValue(@TempDir Path dir) throws IOException {
        Path input = dir.resolve("entities.tsv");
        Files.writeString(input, "CYP2C9\tGene\tPA126\tmaybe\n");

        assertThrows(IllegalArgumentException.class, () -> MappingBuilder.readEntities(input));
    }

    @Test
    void testBuildRoutesShortAllCapsAbbreviationsToCaseSensitiveFile() {
        MappingBuilder.Result result = builder.build(List.of(
                new MappingBuilder.Entity("THE", "Chemical", "PA449905"),
                new MappingBuilder.Entity("5-FU", "Chemical", "PA128406956"),
                new MappingBuilder.Entity("Tylenol", "Chemical", "PA448015")));

        assertEquals(List.of("5 - FU\tChemical\tPA128406956", "THE\tChemical\tPA449905"), result.geneLines());
        assertEquals(List.of("Tylenol\tChemical\tPA448015"), result.otherLines());
    }

    @Test
    void testBuildStripsBracketedQualifierPrefix() {
        MappingBuilder.Result result = builder.build(List.of(
                new MappingBuilder.Entity("[D]Abdominal pain", "Phenotype", "PA446220", false),
                new MappingBuilder.Entity("Abdominal Pain", "Phenotype", "PA446220", true),
                new MappingBuilder.Entity("[M]Familial polyposis coli", "Phenotype", "PA445381", false)));

        // "[D]Abdominal pain" collapses into the preferred "Abdominal Pain" entry
        assertEquals(List.of("Abdominal Pain\tPhenotype\tPA446220", "Familial polyposis coli\tPhenotype\tPA445381"),
                result.otherLines());
    }

    @Test
    void testBuildExcludesBareCodes() {
        MappingBuilder.Result result = builder.build(List.of(
                new MappingBuilder.Entity("2019", "Chemical", "PA448794", false),
                new MappingBuilder.Entity("21522001", "Phenotype", "PA446220", false),
                new MappingBuilder.Entity("D015746", "Phenotype", "PA446220", false),
                new MappingBuilder.Entity("D2HGDH", "Gene", "PA162383590"),
                new MappingBuilder.Entity("1,2-dimethylhydrazine", "Chemical", "PA1")));

        assertEquals(List.of("D2HGDH\tGene\tPA162383590"), result.geneLines());
        assertEquals(List.of("1,2 - dimethylhydrazine\tChemical\tPA1"), result.otherLines());
        assertEquals(3, result.excluded().get(MappingBuilder.EXCLUDED_CODE));
    }

    @Test
    void testBuildExcludesSingleCharacterNames() {
        MappingBuilder.Result result = builder.build(List.of(
                new MappingBuilder.Entity("C", "Chemical", "PA451862"),
                new MappingBuilder.Entity("L", "Chemical", "PA1")));

        assertTrue(result.otherLines().isEmpty());
        assertTrue(result.geneLines().isEmpty());
        assertEquals(2, result.excluded().get(MappingBuilder.EXCLUDED_SINGLE_CHARACTER));
    }

    @Test
    void testMissingTypesReportsExpectedTypesWithNoEntities() {
        List<MappingBuilder.Entity> entities = List.of(
                new MappingBuilder.Entity("CYP2C9", "Gene", "PA126"),
                new MappingBuilder.Entity("warfarin", "Chemical", "PA451906"));

        assertEquals(List.of("Allele", "Phenotype"), MappingBuilder.missingTypes(entities));
    }

    @Test
    void testReadEntitiesSkipsHeaderAndBlankLines(@TempDir Path dir) throws IOException {
        Path input = dir.resolve("entities.tsv");
        Files.writeString(input, "name\ttype\tid\nCYP2C9\tGene\tPA126\n\nwarfarin\tChemical\tPA451906\n");

        assertEquals(List.of(
                new MappingBuilder.Entity("CYP2C9", "Gene", "PA126"),
                new MappingBuilder.Entity("warfarin", "Chemical", "PA451906")),
                MappingBuilder.readEntities(input));
    }

    @Test
    void testReadEntitiesRejectsMalformedLines(@TempDir Path dir) throws IOException {
        Path input = dir.resolve("entities.tsv");
        Files.writeString(input, "CYP2C9\tGene\n");

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> MappingBuilder.readEntities(input));
        assertTrue(e.getMessage().contains("line 1"));
    }

    @Test
    void testGeneratedFilesMatchTokenizedText(@TempDir Path dir) throws IOException {
        Path input = dir.resolve("entities.tsv");
        Files.writeString(input, String.join("\n",
                "HLA-B\tGene\tPA35056",
                "HLA-B*57:01\tHaplotype\tPA165954769",
                "A1BG\tGene\tPA24356",
                "A1BG-AS1\tGene\tPA165392995",
                "5-fluorouracil\tChemical\tPA128406956",
                "abacavir\tChemical\tPA448004"));
        MappingBuilder.writeMappings(builder.build(MappingBuilder.readEntities(input)), dir);

        NerPipeline genes = new NerPipeline(NerPipeline.Type.GENE, dir.resolve(MappingBuilder.GENE_FILE).toString());
        NerPipeline other = new NerPipeline(NerPipeline.Type.OTHER, dir.resolve(MappingBuilder.OTHER_FILE).toString());

        List<String> geneMatches = genes.run("Carriers of HLA-B*57:01, HLA-B and A1BG-AS1.").stream()
                .map(e -> e.getText() + "=" + e.getAccessionId()).toList();
        assertEquals(List.of("HLA-B*57:01=PA165954769", "HLA-B=PA35056", "A1BG-AS1=PA165392995"), geneMatches);

        List<String> otherMatches = other.run("Abacavir, 5-Fluorouracil, rs12345, c.123A>G and g.5C>T.").stream()
                .map(e -> e.getText() + "=" + e.getAccessionId()).toList();
        assertEquals(List.of("Abacavir=PA448004", "5-Fluorouracil=PA128406956", "rs12345=dbSNP", "c.123A>G=HGVS", "g.5C>T.=HGVS"), otherMatches);
    }
}
