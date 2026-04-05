package com.travel.agent.etl;

import io.milvus.client.MilvusServiceClient;
import io.milvus.param.ConnectParam;
import io.milvus.param.R;
import io.milvus.param.collection.HasCollectionParam;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
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
 * 文档入库：清洗 → 分块 →（占位）向量化 → 写入 Milvus 集合。
 */
@Slf4j
@Service
public class IngestionPipeline {

    private static final Pattern WS = Pattern.compile("\\s+");

    @Value("${travel.agent.milvus.host:localhost}")
    private String milvusHost;

    @Value("${travel.agent.milvus.port:19530}")
    private int milvusPort;

    @Value("${travel.agent.milvus.collection:travel_kb}")
    private String collectionName;

    private volatile MilvusServiceClient milvusClient;

    @PostConstruct
    public void init() {
        try {
            ConnectParam param = ConnectParam.newBuilder()
                    .withHost(milvusHost)
                    .withPort(milvusPort)
                    .build();
            milvusClient = new MilvusServiceClient(param);
            log.info("IngestionPipeline Milvus connected {}:{}", milvusHost, milvusPort);
        } catch (Exception e) {
            log.warn("IngestionPipeline: Milvus not available: {}", e.getMessage());
            milvusClient = null;
        }
    }

    @PreDestroy
    public void shutdown() {
        if (milvusClient != null) {
            try {
                milvusClient.close();
            } catch (Exception ignored) {
                // ignore
            }
        }
    }

    /**
     * 处理原始文档：返回结构化块，若 Milvus 可用则检查集合存在性（真实 insert 需与 schema 对齐）。
     */
    public IngestionResult ingestDocument(String title, String rawText, Map<String, String> metadata) {
        String normalized = normalize(rawText);
        List<TextChunk> chunks = chunkByLength(normalized, 512, 64);
        List<String> ids = new ArrayList<>();
        for (TextChunk ch : chunks) {
            ids.add(hashId(title, ch.text()));
        }
        boolean milvusReady = false;
        if (milvusClient != null) {
            try {
                R<Boolean> resp = milvusClient.hasCollection(HasCollectionParam.newBuilder()
                        .withCollectionName(collectionName)
                        .build());
                milvusReady = Boolean.TRUE.equals(resp.getData());
            } catch (Exception e) {
                log.debug("hasCollection: {}", e.getMessage());
            }
        }
        return new IngestionResult(title, chunks.size(), ids, milvusReady, metadata);
    }

    private String normalize(String raw) {
        if (raw == null) {
            return "";
        }
        String t = WS.matcher(raw.trim()).replaceAll(" ");
        return t;
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
            String slice = text.substring(start, end);
            out.add(new TextChunk(idx++, slice));
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
            String s = title + "|" + chunk;
            byte[] digest = md.digest(s.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest).substring(0, 32);
        } catch (NoSuchAlgorithmException e) {
            return UUID.randomUUID().toString().replace("-", "");
        }
    }

    public record TextChunk(int index, String text) {
    }

    public record IngestionResult(
            String title,
            int chunkCount,
            List<String> chunkIds,
            boolean milvusCollectionExists,
            Map<String, String> metadata
    ) {
        public String summary() {
            return String.format(Locale.ROOT, "ingested title=%s chunks=%d milvusOk=%s",
                    title, chunkCount, milvusCollectionExists);
        }
    }
}
