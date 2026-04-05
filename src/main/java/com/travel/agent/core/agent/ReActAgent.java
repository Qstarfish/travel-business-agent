package com.travel.agent.core.agent;

import com.travel.agent.api.dto.StreamChunk;
import com.travel.agent.core.memory.MemoryManager;
import com.travel.agent.core.tools.ToolRegistry;
import com.travel.agent.infrastructure.llm.CircuitBreaker;
import com.travel.agent.infrastructure.llm.ModelRouter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ReAct：Reason → Act → Observe 循环，支持工具调用与流式输出。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReActAgent {

    private static final Pattern ACTION_PATTERN = Pattern.compile(
            "(?is)ACTION:\\s*([a-zA-Z0-9_\\-]+)\\s*\\|\\s*INPUT:\\s*(.+?)(?=\\n(?:THOUGHT|ACTION|FINAL):|$)");
    private static final Pattern FINAL_PATTERN = Pattern.compile("(?is)FINAL:\\s*(.+)");

    private static final String LLM_BREAKER = "openai-chat";

    private final ToolRegistry toolRegistry;
    private final MemoryManager memoryManager;
    private final ModelRouter modelRouter;
    private final CircuitBreaker circuitBreaker;
    private final ChatClient.Builder chatClientBuilder;

    @Value("${travel.agent.max-react-iterations:8}")
    private int maxIterations;

    /**
     * 非流式：完整 ReAct 循环后返回最终自然语言答案。
     */
    public String run(
            String sessionId,
            String traceId,
            String mode,
            String systemPrompt,
            String ragAugmentation,
            String userInput,
            boolean streamingModeIgnored
    ) {
        ChatClient client = modelRouter.selectChatClient(chatClientBuilder, mode);
        List<Message> scratchpad = new ArrayList<>();
        scratchpad.add(new SystemMessage(buildSystemBlock(systemPrompt, ragAugmentation, mode)));
        String recent = memoryManager.formatShortTermForPrompt(sessionId);
        scratchpad.add(new UserMessage("近期对话:\n" + recent + "\n用户当前问题:\n" + userInput));

        for (int step = 0; step < maxIterations; step++) {
            String llmOut = circuitBreaker.execute(
                    LLM_BREAKER,
                    () -> client.prompt(new Prompt(scratchpad)).call().content(),
                    () -> "FINAL: 对话服务暂时不可用，请稍后重试。trace=" + traceId
            );

            Matcher finalM = FINAL_PATTERN.matcher(llmOut);
            if (finalM.find()) {
                return finalM.group(1).trim();
            }

            Matcher actM = ACTION_PATTERN.matcher(llmOut);
            if (!actM.find()) {
                return llmOut.trim();
            }

            String toolName = actM.group(1).trim().toLowerCase(Locale.ROOT);
            String toolInput = actM.group(2).trim();
            String observation = safeToolInvoke(toolName, toolInput);
            scratchpad.add(new UserMessage("模型中间输出:\n" + llmOut));
            scratchpad.add(new UserMessage("OBSERVATION:\n" + observation));
        }
        return "已达到最大推理步数，请简化问题后重试。trace=" + traceId;
    }

    /**
     * 流式：逐步将 LLM token 推送到客户端；若需工具则在本轮结束后同步执行再继续下一轮。
     */
    public Flux<StreamChunk> runStream(
            String sessionId,
            String traceId,
            String mode,
            String systemPrompt,
            String ragAugmentation,
            String userInput
    ) {
        return Flux.create(sink -> Schedulers.boundedElastic().schedule(() -> {
            try {
                ChatClient client = modelRouter.selectChatClient(chatClientBuilder, mode);
                List<Message> scratchpad = new ArrayList<>();
                scratchpad.add(new SystemMessage(buildSystemBlock(systemPrompt, ragAugmentation, mode)));
                String recent = memoryManager.formatShortTermForPrompt(sessionId);
                scratchpad.add(new UserMessage("近期对话:\n" + recent + "\n用户当前问题:\n" + userInput));

                for (int step = 0; step < maxIterations; step++) {
                    StringBuilder buf = new StringBuilder();
                    try {
                        client.prompt(new Prompt(scratchpad))
                                .stream()
                                .content()
                                .doOnNext(token -> {
                                    buf.append(token);
                                    sink.next(StreamChunk.builder()
                                            .type("delta")
                                            .content(token)
                                            .agentMode(mode)
                                            .traceId(traceId)
                                            .build());
                                })
                                .doOnComplete(() -> circuitBreaker.recordSuccess(LLM_BREAKER))
                                .doOnError(e -> circuitBreaker.recordFailure(LLM_BREAKER))
                                .blockLast(Duration.ofMinutes(3));
                    } catch (Exception ex) {
                        circuitBreaker.recordFailure(LLM_BREAKER);
                        throw ex;
                    }

                    String llmOut = buf.toString();
                    Matcher finalM = FINAL_PATTERN.matcher(llmOut);
                    if (finalM.find()) {
                        String answer = finalM.group(1).trim();
                        memoryManager.appendAssistantMessage(sessionId, answer);
                        sink.complete();
                        return;
                    }
                    Matcher actM = ACTION_PATTERN.matcher(llmOut);
                    if (!actM.find()) {
                        memoryManager.appendAssistantMessage(sessionId, llmOut.trim());
                        sink.complete();
                        return;
                    }
                    String toolName = actM.group(1).trim().toLowerCase(Locale.ROOT);
                    String toolInput = actM.group(2).trim();
                    String observation = safeToolInvoke(toolName, toolInput);
                    scratchpad.add(new UserMessage("模型中间输出:\n" + llmOut));
                    scratchpad.add(new UserMessage("OBSERVATION:\n" + observation));
                }
                sink.error(new IllegalStateException("已达到最大推理步数"));
            } catch (Exception e) {
                sink.error(e);
            }
        }));
    }

    private String buildSystemBlock(String systemPrompt, String ragAugmentation, String mode) {
        StringBuilder sb = new StringBuilder();
        sb.append(systemPrompt).append("\n\n");
        sb.append("当前子域模式: ").append(mode).append("\n");
        sb.append("可用工具:\n").append(toolRegistry.describeTools()).append("\n");
        sb.append("输出协议：若需调用工具，使用格式 ACTION: tool_name | INPUT: ... ；结束回答使用 FINAL: ...\n");
        if (ragAugmentation != null && !ragAugmentation.isBlank()) {
            sb.append("检索到的参考资料:\n").append(ragAugmentation);
        }
        return sb.toString();
    }

    private String safeToolInvoke(String toolName, String toolInput) {
        try {
            return toolRegistry.invoke(toolName, toolInput);
        } catch (Exception e) {
            log.warn("Tool {} failed: {}", toolName, e.getMessage());
            return "[tool error] " + e.getMessage();
        }
    }
}
