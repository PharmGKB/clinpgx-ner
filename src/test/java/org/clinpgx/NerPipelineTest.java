package org.clinpgx;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class NerPipelineTest {

    private static NerPipeline genePipeline;
    private static NerPipeline otherPipeline;

    @BeforeAll
    static void setUp() {
        genePipeline = new NerPipeline(NerPipeline.Type.GENE);
        otherPipeline = new NerPipeline(NerPipeline.Type.OTHER);
    }

    @Test
    void testGenePipelineRecognizesKnownGene() {
        List<DocumentEntity> entities = genePipeline.run("CYP2C9 is a gene");
        
        assertFalse(entities.isEmpty());
        assertEquals("CYP2C9", entities.getFirst().getText());
    }

    @Test
    void testOtherPipelineRecognizesKnownOther() {
        List<DocumentEntity> entities = otherPipeline.run("The patient has rs12345 variant");
        
        assertFalse(entities.isEmpty());
    }

    @Test
    void testRunReturnsEmptyListForNullInput() {
        List<DocumentEntity> entities = genePipeline.run(null);
        
        assertTrue(entities.isEmpty());
    }

    @Test
    void testRunReturnsEmptyListForUnknownText() {
        List<DocumentEntity> entities = genePipeline.run("This is some random text with no known entities");
        
        assertTrue(entities.isEmpty());
    }

    @Test
    void testRunAllCombinesBothPipelineResults() {
        List<DocumentEntity> entities = NerPipeline.runAll("CYP2C9 variant rs12345");
        
        assertFalse(entities.isEmpty());
        assertNotNull(entities);
        assertNotNull(entities.getFirst());
        assertEquals("CYP2C9", entities.getFirst().getText());
        assertEquals("rs12345", entities.get(1).getText());
    }

    @Test
    void testDocumentEntityHasCorrectProperties() {
        List<DocumentEntity> entities = genePipeline.run("CYP2C9");
        
        if (!entities.isEmpty()) {
            DocumentEntity entity = entities.getFirst();
            assertEquals("CYP2C9", entity.getText());
            assertTrue(entity.getBegin() >= 0);
            assertTrue(entity.getEnd() > entity.getBegin());
        }
    }
}
