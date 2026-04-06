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

    //并发发起向量检索、关键词检索
    public List<Map<String, String>> retrieveParallel(String sessionId, String query, String mode) {
        //把这两个任务扔到线程池 pool 里异步执行
        CompletableFuture<List<Map<String, String>>> vectorFuture = CompletableFuture.supplyAsync(
                () -> vectorChannel(query, mode), pool);
        CompletableFuture<List<Map<String, String>>> keywordFuture = CompletableFuture.supplyAsync(
                () -> keywordChannel(query, mode), pool);

        List<Map<String, String>> merged = new ArrayList<>();
        try {
            merged.addAll(vectorFuture.join());
            merged.addAll(keywordFuture.join());
        } catch (Exception e) {
            //todo 现在更偏“尽量不让检索失败把主流程打断”，而不是“精细区分哪一路失败”。
            //如果其中一个 join() 报错，后面的结果也可能拿不到，最后就只会返回当前 merged 里已经成功加进去的内容
            log.warn("retrieveParallel join failed: {}", e.getMessage());
        }
        //统一重排和裁剪
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

    //做多路检索后的重排序
    private List<Map<String, String>> rerankAndTruncate(List<Map<String, String>> merged, String query) {
        //todo 不是严格意义上的“高级 reranker”，而是一个简化版的启发式重排器。
        //1.去重
        Map<String, Map<String, String>> dedup = new HashMap<>();
        for (Map<String, String> row : merged) {
            //用 title + text.hashCode() 作为去重 key
            String key = row.getOrDefault("title", "") + "|" + row.getOrDefault("text", "").hashCode();
            /**
             * 去重策略是：
             * 同内容只留一条
             * 重复时保留分更高的版本
             */
            dedup.merge(key, row, (oldRow, newRow) -> {
                double o = Double.parseDouble(oldRow.getOrDefault("score", "0"));
                double n = Double.parseDouble(newRow.getOrDefault("score", "0"));
                return n > o ? newRow : oldRow;
            });
        }

        //2.预处理查询词
        String q = query.toLowerCase(Locale.ROOT);
        //把去重后的结果转成 list，准备重排
        List<Map<String, String>> list = new ArrayList<>(dedup.values());

        //3.给每条结果重新加一点 bonus 分
        /**
         * 比如查询是：
         *
         * 酒店审批
         * 如果某条文本里也包含“酒”“店”“审”“批”这些字，那它会获得额外 bonus。
         *
         * 所以它不是严格的词级匹配，而是一个非常轻量的“字符覆盖率加权”。
         *
         * 最终把：
         * base + bonus
         * 重新写回 score 字段。
         */
        for (Map<String, String> row : list) {
            double base = Double.parseDouble(row.getOrDefault("score", "0"));
            String text = row.getOrDefault("text", "").toLowerCase(Locale.ROOT);
            //遍历查询里的每个字符
            //只要这个字符在当前文本里出现过，就记一次
            //每命中一个字符，加 0.01
            double bonus = q.chars().filter(ch -> text.indexOf(ch) >= 0).count() * 0.01;
            row.put("score", String.format(Locale.ROOT, "%.4f", base + bonus));
        }

        //4.按新分数排序
        list.sort(Comparator.comparingDouble(a -> -Double.parseDouble(a.getOrDefault("score", "0"))));
        //5.截断返回前 K 条,避免上下文太长，也避免把低相关内容送给模型
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
