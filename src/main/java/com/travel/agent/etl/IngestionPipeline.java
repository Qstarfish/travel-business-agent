package com.travel.agent.etl;

import com.travel.agent.core.rag.PgVectorKnowledgeChunk;
import com.travel.agent.core.rag.PgVectorKnowledgeRepository;
import com.travel.agent.infrastructure.embedding.TextEmbeddingService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 文档入库：清洗 -> 分块 -> embedding -> 写入 PostgreSQL/pgvector。
 */
@Slf4j
@Service
public class IngestionPipeline {

    private static final Pattern WS = Pattern.compile("\\s+");

    private final PgVectorKnowledgeRepository repository;
    private final TextEmbeddingService embeddingService;

    public IngestionPipeline(PgVectorKnowledgeRepository repository, TextEmbeddingService embeddingService) {
        this.repository = repository;
        this.embeddingService = embeddingService;
    }

    public IngestionResult ingestDocument(String title, String rawText, Map<String, String> metadata) {
        String normalized = normalize(rawText);
        List<TextChunk> chunks = chunkByLength(normalized, 512, 64);
        List<String> ids = new ArrayList<>();
        boolean vectorStoreReady = repository.isAvailable();

        if (vectorStoreReady) {
            String docId = metadata == null ? hashId(title, normalized) : metadata.getOrDefault("docId", hashId(title, normalized));
            String mode = metadata == null ? "general" : metadata.getOrDefault("mode", "general");
            List<PgVectorKnowledgeChunk> rows = new ArrayList<>();
            for (TextChunk ch : chunks) {
                String chunkId = hashId(title, ch.text());
                ids.add(chunkId);
                rows.add(new PgVectorKnowledgeChunk(
                        chunkId,
                        docId,
                        ch.index(),
                        title,
                        ch.text(),
                        mode,
                        metadata == null ? Map.of() : metadata,
                        embeddingService.embed(ch.text())
                ));
            }
            repository.saveAll(rows);
        } else {
            for (TextChunk ch : chunks) {
                ids.add(hashId(title, ch.text()));
            }
        }
        return new IngestionResult(title, chunks.size(), ids, vectorStoreReady, metadata);
    }

    private String normalize(String raw) {
        if (raw == null) {
            return "";
        }
        return WS.matcher(raw.trim()).replaceAll(" ");
    }

    private List<TextChunk> chunkByLength(String text, int maxChars, int overlap) {
        List<TextChunk> out = new ArrayList<>();
        if (text.isEmpty()) {
            return out;
        }
        int start = 0;
        int n = text.length();
        int idx = 0;
        while (start < n) {
            int end = Math.min(n, start + maxChars);
            out.add(new TextChunk(idx++, text.substring(start, end)));
            if (end >= n) {
                break;
            }
            start = Math.max(0, end - overlap);
        }
        return out;
    }

    private String hashId(String title, String chunk) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest((title + "|" + chunk).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest).substring(0, 32);
        } catch (NoSuchAlgorithmException e) {
            log.warn("hash id fallback: {}", e.getMessage());
            return UUID.randomUUID().toString().replace("-", "");
        }
    }

    public record TextChunk(int index, String text) {
    }

    public record IngestionResult(
            String title,
            int chunkCount,
            List<String> chunkIds,
            boolean vectorStoreReady,
            Map<String, String> metadata
    ) {
        public String summary() {
            return String.format(Locale.ROOT, "ingested title=%s chunks=%d pgvectorOk=%s",
                    title, chunkCount, vectorStoreReady);
        }
    }
}
