package com.travel.agent.core.rag;

import io.milvus.client.MilvusServiceClient;
import io.milvus.param.ConnectParam;
import jakarta.annotation.PostConstruct;
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

    @Value("${travel.agent.rag.vector-top-k:5}")
    private int vectorTopK;

    @Value("${travel.agent.rag.keyword-top-k:5}")
    private int keywordTopK;

    @Value("${travel.agent.rag.rerank-top-k:8}")
    private int rerankTopK;

    @Value("${travel.agent.milvus.host:localhost}")
    private String milvusHost;

    @Value("${travel.agent.milvus.port:19530}")
    private int milvusPort;

    @Value("${travel.agent.milvus.collection:travel_kb}")
    private String collectionName;

    private volatile MilvusServiceClient milvusClient;

    @PostConstruct
    public void connectMilvus() {
        try {
            ConnectParam param = ConnectParam.newBuilder()
                    .withHost(milvusHost)
                    .withPort(milvusPort)
                    .build();
            milvusClient = new MilvusServiceClient(param);
            log.info("Milvus client ready {}:{} collection={}", milvusHost, milvusPort, collectionName);
        } catch (Exception e) {
            log.warn("Milvus unavailable, vector channel will degrade: {}", e.getMessage());
            milvusClient = null;
        }
    }

    @PreDestroy
    public void closeMilvus() {
        if (milvusClient != null) {
            try {
                milvusClient.close();
            } catch (Exception ignored) {
                // ignore
            }
        }
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
            merged.addAll(keywordFuture.join());
        } catch (Exception e) {
            log.warn("retrieveParallel join failed: {}", e.getMessage());
        }
        return rerankAndTruncate(merged, query);
    }

    private List<Map<String, String>> vectorChannel(String query, String mode) {
        List<Map<String, String>> out = new ArrayList<>();
        if (milvusClient == null) {
            return syntheticVectorFallback(query, mode);
        }
        try {
            // 生产环境在此调用 embedding + Milvus search；无向量服务时降级为关键词近似
            return syntheticVectorFallback(query, mode);
        } catch (Exception e) {
            log.debug("vector search failed: {}", e.getMessage());
            return syntheticVectorFallback(query, mode);
        }
    }

    /**
     * 无 embedding 服务时的确定性降级：按 query 分词重叠度模拟向量分数。
     */
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
        for (Map<String, String> row : list) {
            double base = Double.parseDouble(row.getOrDefault("score", "0"));
            String text = row.getOrDefault("text", "").toLowerCase(Locale.ROOT);
            double bonus = q.chars().filter(ch -> text.indexOf(ch) >= 0).count() * 0.01;
            row.put("score", String.format(Locale.ROOT, "%.4f", base + bonus));
        }
        list.sort(Comparator.comparingDouble(a -> -Double.parseDouble(a.getOrDefault("score", "0"))));
        return list.stream().limit(rerankTopK).collect(Collectors.toList());
    }

    private List<Map<String, String>> seedKnowledge(String mode) {
        List<Map<String, String>> rows = new ArrayList<>();
        rows.add(Map.of(
                "title", "国内差标-机票",
                "text", "经济舱全价及以下可预订；超标需上级审批。京沪广深高峰期建议提前7天预订。"
        ));
        rows.add(Map.of(
                "title", "酒店标准",
                "text", "一线城市上限600元/晚，二线城市450元/晚，含税费。"
        ));
        rows.add(Map.of(
                "title", "火车票",
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
