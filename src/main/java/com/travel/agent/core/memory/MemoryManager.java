package com.travel.agent.core.memory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 短期：Redis 滑动窗口对话；长期：向量摘要占位（与 Milvus 集合对接）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MemoryManager {

    private static final String MSG_KEY = "agent:session:%s:messages";
    private static final String LT_PREFIX = "agent:lt:";

    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;

    @Value("${travel.agent.short-term-memory-max-messages:40}")
    private int maxMessages;

    @Value("${travel.agent.long-term-top-k:6}")
    private int longTermTopK;

    public void appendUserMessage(String sessionId, String text) {
        append(sessionId, "user", text);
    }

    public void appendAssistantMessage(String sessionId, String text) {
        append(sessionId, "assistant", text);
    }

    private void append(String sessionId, String role, String text) {
        String key = String.format(MSG_KEY, sessionId);
        try {
            //存role、消息正文text、时间戳ts
            Map<String, Object> rec = new HashMap<>();
            rec.put("role", role);
            rec.put("text", text);
            rec.put("ts", Instant.now().toString());

            String json = objectMapper.writeValueAsString(rec);
            //将消息rightPush 到 Redis List 尾部，只保留最近maxMessages 条，默认 40 条，48 小时过期
            stringRedisTemplate.opsForList().rightPush(key, json);
            stringRedisTemplate.opsForList().trim(key, -maxMessages, -1);
            stringRedisTemplate.expire(key, 48, TimeUnit.HOURS);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("memory serialize failed", e);
        }
    }

    // 将短期记忆返回给模型
    public String formatShortTermForPrompt(String sessionId) {
        String key = String.format(MSG_KEY, sessionId);
        //从 Redis 取最近 20 条消息
        List<String> raw = stringRedisTemplate.opsForList().range(key, -20, -1);
        if (raw == null || raw.isEmpty()) {
            return "(无)";
        }
        StringBuilder sb = new StringBuilder();
        for (String line : raw) {
            try {
                Map<?, ?> m = objectMapper.readValue(line, Map.class);
                sb.append(m.get("role")).append(": ").append(m.get("text")).append("\n");
            } catch (JsonProcessingException e) {
                sb.append(line).append("\n");
            }
        }
        return sb.toString();
    }

    //取最近 10 条消息的 role，判断当前对话大概处于什么阶段，如：刚开场、收集需求……
    public List<String> getRecentRoles(String sessionId) {
        String key = String.format(MSG_KEY, sessionId);
        List<String> raw = stringRedisTemplate.opsForList().range(key, -10, -1);
        List<String> roles = new ArrayList<>();
        if (raw == null) {
            return roles;
        }
        for (String line : raw) {
            try {
                Map<?, ?> m = objectMapper.readValue(line, Map.class);
                roles.add(String.valueOf(m.get("role")));
            } catch (JsonProcessingException ignored) {
                // skip
            }
        }
        return roles;
    }

    /**
     * 将摘要写入 Redis 列表，供后续离线向量化写入 Milvus。
     */
    public void saveLongTermSummary(String sessionId, String userInput, String assistantReply) {
        try {
            Map<String, Object> doc = new HashMap<>();
            doc.put("sessionId", sessionId);
            doc.put("user", userInput);
            doc.put("assistant", assistantReply);
            doc.put("ts", Instant.now().toString());
            String json = objectMapper.writeValueAsString(doc);
            String key = LT_PREFIX + sessionId;
            stringRedisTemplate.opsForList().leftPush(key, json);
            stringRedisTemplate.opsForList().trim(key, 0, longTermTopK - 1);
            stringRedisTemplate.expire(key, 30, TimeUnit.DAYS);
        } catch (JsonProcessingException e) {
            log.warn("long-term memory save failed: {}", e.getMessage());
        }
    }
}
