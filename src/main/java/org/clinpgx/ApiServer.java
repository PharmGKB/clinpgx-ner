package org.clinpgx;

import io.javalin.Javalin;
import io.javalin.json.JavalinJackson;

import java.util.ArrayList;
import java.util.List;

public class ApiServer {
    private static final int DEFAULT_PORT = 7001;
    // Resolved before the pipelines load so an invalid PORT fails fast
    private static final int PORT = port();
    private static final NerPipeline genePipeline = new NerPipeline(NerPipeline.Type.GENE);
    private static final NerPipeline otherPipeline = new NerPipeline(NerPipeline.Type.OTHER);

    public static void main(String[] args) {
        Javalin app = Javalin.create(config -> {
            config.jsonMapper(new JavalinJackson());
            config.routes.post("/ner", ctx -> {
                String text = ctx.body();
                List<DocumentEntity> entities = new ArrayList<>(genePipeline.run(text));
                entities.addAll(otherPipeline.run(text));
                ctx.json(entities);
            });
        }).start(PORT);
    }

    private static int port() {
        String port = System.getenv("PORT");
        if (port == null || port.isBlank()) {
            return DEFAULT_PORT;
        }
        try {
            return Integer.parseInt(port.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("PORT must be an integer, got: " + port, e);
        }
    }
}
