package com.travel.agent.core.orchestrator;

import com.travel.agent.api.dto.ChatResponse;
import com.travel.agent.api.dto.StreamChunk;
import com.travel.agent.core.agent.ReActAgent;
import com.travel.agent.core.intent.IntentRecognizer;
import com.travel.agent.core.memory.MemoryManager;
import com.travel.agent.core.prompt.PromptStateMachine;
import com.travel.agent.core.rag.MultiChannelRetriever;
import com.travel.agent.infrastructure.trace.TraceContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 商旅 Agent 总编排：意图识别 → 模式与子域路由 → ReAct+RAG → 短期/长期记忆与追踪。
 * <p>
 * 非流式与流式共用同一套意图与检索前置逻辑，仅在最终生成阶段区分同步与 SSE。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentOrchestrator {

    private final IntentRecognizer intentRecognizer;
    private final MemoryManager memoryManager;
    private final MultiChannelRetriever multiChannelRetriever;
    private final ReActAgent reActAgent;
    private final PromptStateMachine promptStateMachine;

    @Value("${travel.agent.name:商旅助手}")
    private String agentName;

    /**
     * 同步对话：写入用户消息 → 识别意图 → 按模式路由 → RAG → ReAct → 持久化助手回复。
     */
    public ChatResponse processMessage(String sessionId, String userInput) {
        long start = System.nanoTime();
        String traceId = TraceContext.ensureTraceId();
        try {
            memoryManager.appendUserMessage(sessionId, userInput);

            IntentRecognizer.IntentResult intent = intentRecognizer.recognize(sessionId, userInput);
            String mode = normalizeAgentMode(intent.getMode());
            log.info("[{}] mode={} confidence={} source={} trace={}",
                    sessionId, mode, intent.getConfidence(), intent.getSource(), traceId);

            String effectiveMode = routeSubAgent(mode, userInput);
            PromptStateMachine.ConversationState state = promptStateMachine.resolveState(sessionId, userInput, effectiveMode);
            String systemPrompt = promptStateMachine.buildSystemPrompt(state, effectiveMode, agentName);

            List<Map<String, String>> ragContext = multiChannelRetriever.retrieveParallel(sessionId, userInput, effectiveMode);
            String augmented = buildAugmentedContext(ragContext);

            String reply = reActAgent.run(
                    sessionId,
                    traceId,
                    effectiveMode,
                    systemPrompt,
                    augmented,
                    userInput,
                    false
            );

            memoryManager.appendAssistantMessage(sessionId, reply);
            maybePersistLongTerm(sessionId, userInput, reply, intent.getConfidence());

            long latencyMs = (System.nanoTime() - start) / 1_000_000L;
            return ChatResponse.builder()
                    .sessionId(sessionId)
                    .reply(reply)
                    .agentMode(effectiveMode)
                    .traceId(traceId)
                    .citations(ragContext)
                    .latencyMs(latencyMs)
                    .build();
        } catch (Exception e) {
            log.error("processMessage failed trace={}", traceId, e);
            long latencyMs = (System.nanoTime() - start) / 1_000_000L;
            return ChatResponse.builder()
                    .sessionId(sessionId)
                    .reply("处理请求时出现问题：" + safeMsg(e))
                    .agentMode("error")
                    .traceId(traceId)
                    .latencyMs(latencyMs)
                    .build();
        } finally {
            TraceContext.clear();
        }
    }

    /**
     * 流式：在弹性线程中完成意图与检索，再将 token 流拼接为同一会话记忆策略。
     */
    public Flux<StreamChunk> processMessageStream(String sessionId, String userInput) {
        String traceId = TraceContext.ensureTraceId();
        return Mono.fromCallable(() -> {
                    memoryManager.appendUserMessage(sessionId, userInput);
                    IntentRecognizer.IntentResult intent = intentRecognizer.recognize(sessionId, userInput);
                    String mode = normalizeAgentMode(intent.getMode());
                    String effectiveMode = routeSubAgent(mode, userInput);
                    PromptStateMachine.ConversationState state =
                            promptStateMachine.resolveState(sessionId, userInput, effectiveMode);
                    String systemPrompt = promptStateMachine.buildSystemPrompt(state, effectiveMode, agentName);
                    List<Map<String, String>> ragContext =
                            multiChannelRetriever.retrieveParallel(sessionId, userInput, effectiveMode);
                    String augmented = buildAugmentedContext(ragContext);
                    return new StreamPrep(sessionId, traceId, effectiveMode, systemPrompt, augmented, userInput, intent);
                })
                .subscribeOn(Schedulers.boundedElastic())
                .flatMapMany(prep -> reActAgent.runStream(
                        prep.sessionId(),
                        prep.traceId(),
                        prep.mode(),
                        prep.systemPrompt(),
                        prep.augmented(),
                        prep.userInput()
                ).concatWith(Flux.defer(() -> {
                    if (prep.intent().getConfidence() < 0.55) {
                        memoryManager.saveLongTermSummary(prep.sessionId(), prep.userInput(), "[stream]");
                    }
                    TraceContext.clear();
                    return Flux.just(StreamChunk.builder()
                            .type("done")
                            .content("")
                            .agentMode(prep.mode())
                            .traceId(prep.traceId())
                            .build());
                })))
                .doOnError(err -> TraceContext.clear())
                .onErrorResume(err -> {
                    log.error("stream pipeline error", err);
                    TraceContext.clear();
                    return Flux.just(StreamChunk.builder()
                            .type("error")
                            .content(err.getMessage() != null ? err.getMessage() : "stream error")
                            .traceId(traceId)
                            .build());
                });
    }

    /**
     * 将 LLM/规则输出的模式统一为内部子域标识，避免大小写或别名导致路由失败。
     */
    private String normalizeAgentMode(String mode) {
        if (mode == null || mode.isBlank()) {
            return "general";
        }
        return mode.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * 子 Agent 路由：在粗粒度意图之上叠加关键词与业务规则，映射到实际提示词与工具集。
     */
    private String routeSubAgent(String mode, String userInput) {
        String text = userInput == null ? "" : userInput.toLowerCase(Locale.ROOT);
        if ("rag".equals(mode)) {
            return "rag";
        }
        if ("booking".equals(mode) && (text.contains("超标") || text.contains("审批") || text.contains("差标"))) {
            return "policy";
        }
        return mode;
    }

    private void maybePersistLongTerm(String sessionId, String userInput, String reply, double confidence) {
        if (confidence < 0.55) {
            memoryManager.saveLongTermSummary(sessionId, userInput, reply);
        }
    }

    private String buildAugmentedContext(List<Map<String, String>> ragContext) {
        if (ragContext == null || ragContext.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        int i = 1;
        for (Map<String, String> row : ragContext) {
            String title = row.getOrDefault("title", "snippet");
            String text = row.getOrDefault("text", "");
            sb.append("[").append(i++).append("] ").append(title).append(": ").append(text).append("\n");
        }
        return sb.toString();
    }

    private static String safeMsg(Exception e) {
        String m = e.getMessage();
        return m != null ? m : e.getClass().getSimpleName();
    }

    private record StreamPrep(
            String sessionId,
            String traceId,
            String mode,
            String systemPrompt,
            String augmented,
            String userInput,
            IntentRecognizer.IntentResult intent
    ) {
    }
}
