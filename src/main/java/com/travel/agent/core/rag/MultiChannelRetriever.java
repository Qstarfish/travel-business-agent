package com.travel.agent.core.rag;

import com.travel.agent.infrastructure.embedding.TextEmbeddingService;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

/**
 * 多路召回：向量通道 + 关键词通道并行，合并后按分数重排截断。
 */
@Slf4j
@Component
public class MultiChannelRetriever {

    private final ExecutorService pool = Executors.newFixedThreadPool(8);
    private final PgVectorKnowledgeRepository repository;
    private final TextEmbeddingService embeddingService;

    @Value("${travel.agent.rag.vector-top-k:5}")
    private int vectorTopK = 5;

    @Value("${travel.agent.rag.keyword-top-k:5}")
    private int keywordTopK = 5;

    @Value("${travel.agent.rag.rerank-top-k:8}")
    private int rerankTopK = 8;

    public MultiChannelRetriever(PgVectorKnowledgeRepository repository, TextEmbeddingService embeddingService) {
        this.repository = repository;
        this.embeddingService = embeddingService;
    }

    @PreDestroy
    public void shutdown() {
        pool.shutdown();
    }

    public List<Map<String, String>> retrieveParallel(String sessionId, String query, String mode) {
        CompletableFuture<List<Map<String, String>>> vectorFuture = CompletableFuture.supplyAsync(
                () -> vectorChannel(query, mode), pool);
        CompletableFuture<List<Map<String, String>>> keywordFuture = CompletableFuture.supplyAsync(
                () -> keywordChannel(query, mode), pool);

        List<Map<String, String>> merged = new ArrayList<>();
        try {
            merged.addAll(vectorFuture.join());
        } catch (Exception e) {
            log.warn("vector retrieve failed: {}", e.getMessage());
        }
        try {
            merged.addAll(keywordFuture.join());
        } catch (Exception e) {
            log.warn("keyword retrieve failed: {}", e.getMessage());
        }
        return rerankAndTruncate(merged, query);
    }

    private List<Map<String, String>> vectorChannel(String query, String mode) {
        if (!repository.isAvailable()) {
            return syntheticVectorFallback(query, mode);
        }
        try {
            float[] embedding = embeddingService.embed(query);
            List<Map<String, String>> rows = repository.searchSimilar(embedding, mode, vectorTopK);
            return rows.isEmpty() ? syntheticVectorFallback(query, mode) : rows;
        } catch (Exception e) {
            log.debug("pgvector search failed: {}", e.getMessage());
            return syntheticVectorFallback(query, mode);
        }
    }

    private List<Map<String, String>> syntheticVectorFallback(String query, String mode) {
        List<Map<String, String>> base = seedKnowledge(mode);
        String[] qtok = query.toLowerCase(Locale.ROOT).split("\\s+");
        List<Map<String, String>> scored = new ArrayList<>();
        for (Map<String, String> row : base) {
            String text = (row.get("text") + " " + row.get("title")).toLowerCase(Locale.ROOT);
            double score = 0;
            for (String t : qtok) {
                if (t.length() > 1 && text.contains(t)) {
                    score += 1.0;
                }
            }
            Map<String, String> copy = new HashMap<>(row);
            copy.put("score", String.format(Locale.ROOT, "%.4f", score + 0.01));
            scored.add(copy);
        }
        return scored.stream()
                .sorted(Comparator.comparingDouble(a -> -Double.parseDouble(a.getOrDefault("score", "0"))))
                .limit(vectorTopK)
                .collect(Collectors.toList());
    }

    private List<Map<String, String>> keywordChannel(String query, String mode) {
        List<Map<String, String>> base = seedKnowledge(mode);
        String q = query.toLowerCase(Locale.ROOT);
        List<Map<String, String>> scored = new ArrayList<>();
        for (Map<String, String> row : base) {
            String text = (row.get("text") + " " + row.get("title")).toLowerCase(Locale.ROOT);
            long hits = 0;
            for (String term : q.split("\\s+")) {
                if (term.length() > 1 && text.contains(term)) {
                    hits++;
                }
            }
            Map<String, String> copy = new HashMap<>(row);
            copy.put("score", String.valueOf(hits + 0.5));
            scored.add(copy);
        }
        return scored.stream()
                .sorted(Comparator.comparingDouble(a -> -Double.parseDouble(a.getOrDefault("score", "0.5"))))
                .limit(keywordTopK)
                .collect(Collectors.toList());
    }

    private List<Map<String, String>> rerankAndTruncate(List<Map<String, String>> merged, String query) {
        Map<String, Map<String, String>> dedup = new HashMap<>();
        for (Map<String, String> row : merged) {
            String key = row.getOrDefault("title", "") + "|" + row.getOrDefault("text", "").hashCode();
            dedup.merge(key, row, (oldRow, newRow) -> {
                double o = Double.parseDouble(oldRow.getOrDefault("score", "0"));
                double n = Double.parseDouble(newRow.getOrDefault("score", "0"));
                return n > o ? newRow : oldRow;
            });
        }
        String q = query.toLowerCase(Locale.ROOT);
        List<Map<String, String>> list = new ArrayList<>(dedup.values());
        List<Map<String, String>> reranked = new ArrayList<>();
        for (Map<String, String> row : list) {
            Map<String, String> mutable = new HashMap<>(row);
            double base = Double.parseDouble(row.getOrDefault("score", "0"));
            String text = row.getOrDefault("text", "").toLowerCase(Locale.ROOT);
            double bonus = q.chars().filter(ch -> text.indexOf(ch) >= 0).count() * 0.01;
            mutable.put("score", String.format(Locale.ROOT, "%.4f", base + bonus));
            reranked.add(mutable);
        }
        reranked.sort(Comparator.comparingDouble(a -> -Double.parseDouble(a.getOrDefault("score", "0"))));
        return reranked.stream().limit(rerankTopK).collect(Collectors.toList());
    }

    private List<Map<String, String>> seedKnowledge(String mode) {
        List<Map<String, String>> rows = new ArrayList<>();
        rows.add(Map.of(
                "title", "国内差标-机票",
                "text", "经济舱全价及以下可预订；超标需上级审批。京沪广深高峰期建议提前7天预订。"
        ));
        rows.add(Map.of(
                "title", "酒店标准",
                "text", "一线城市上限500元/晚，二线城市450元/晚，含税费。"
        ));
        rows.add(Map.of(
                "title", "火车票标准",
                "text", "二等座为标准；一等座、商务座需说明理由并审批。"
        ));
        if ("policy".equals(mode)) {
            rows.add(Map.of("title", "合规提示", "text", "所有订单需关联出差申请单号。"));
        }
        if ("booking".equals(mode)) {
            rows.add(Map.of("title", "预订流程", "text", "先选行程再比价，确认差标后再支付。"));
        }
        return rows;
    }
}
