package com.smartsupply.config;

import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.embedding.EmbeddingResponseMetadata;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Mock Embedding：无真实 Embedding 端点时，用确定性伪向量保证 RAG 链路可演示。
 * 维度跟随 spring.ai.vectorstore.pgvector.dimensions（默认 1024），保证与 pgvector/H2 表及 VectorStore 一致；
 * 余弦相似度可计算，但伪向量不具备语义，检索质量不代表真实效果。
 */
public class MockEmbeddingModel implements EmbeddingModel {

    public static final int DEFAULT_DIMENSIONS = 1024;

    private final int dims;

    public MockEmbeddingModel() {
        this(DEFAULT_DIMENSIONS);
    }

    public MockEmbeddingModel(int dims) {
        this.dims = dims;
    }

    @Override
    public EmbeddingResponse call(EmbeddingRequest request) {
        List<String> texts = new ArrayList<>();
        for (Object instr : request.getInstructions()) {
            if (instr instanceof String s) texts.add(s);
            else if (instr instanceof Document d) texts.add(d.getText());
            else texts.add(String.valueOf(instr));
        }
        List<Embedding> embeddings = new ArrayList<>();
        for (int idx = 0; idx < texts.size(); idx++) {
            float[] vec = pseudoVector(texts.get(idx), dims);
            embeddings.add(new Embedding(vec, idx));
        }
        return new EmbeddingResponse(embeddings, new EmbeddingResponseMetadata());
    }

    @Override
    public float[] embed(Document document) {
        return pseudoVector(document.getText(), dims);
    }

    private static float[] pseudoVector(String text, int dims) {
        Random r = new Random(text == null ? 0 : text.hashCode());
        float[] v = new float[dims];
        float norm = 0;
        for (int i = 0; i < dims; i++) {
            v[i] = r.nextFloat() * 2 - 1;
            norm += v[i] * v[i];
        }
        norm = (float) Math.sqrt(norm);
        for (int i = 0; i < dims; i++) v[i] /= norm;
        return v;
    }
}
