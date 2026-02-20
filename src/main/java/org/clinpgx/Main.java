package org.clinpgx;

import java.util.List;

public class Main {
    public static void main(String[] args) {
        List<DocumentEntity> list = NerPipeline.runAll("The patient was prescribed Aspirin for the CYP2C9 mutation rs12345. This is another mention of aspirin with lower case.");
        for (DocumentEntity entity : list) {
            System.out.println(entity);
        }
    }
}
