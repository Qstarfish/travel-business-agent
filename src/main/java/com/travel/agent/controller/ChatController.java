package com.travel.agent.controller;

import com.travel.agent.api.dto.ChatRequest;
import com.travel.agent.api.dto.ChatResponse;
import com.travel.agent.api.dto.StreamChunk;
import com.travel.agent.core.orchestrator.AgentOrchestrator;
import com.travel.agent.infrastructure.trace.TraceContext;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.scheduler.Schedulers;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Slf4j
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class ChatController {

    private static final long SSE_TIMEOUT_MS = 120_000L;

    private final AgentOrchestrator agentOrchestrator;

    private final ExecutorService sseExecutor = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "sse-chat-" + System.nanoTime());
        t.setDaemon(true);
        return t;
    });

    @PostMapping("/chat")
    public ResponseEntity<ChatResponse> chat(@Valid @RequestBody ChatRequest request) {
        try {
            ChatResponse response = agentOrchestrator.processMessage(request.getSessionId(), request.getMessage());
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Chat failed session={}", request.getSessionId(), e);
            return ResponseEntity.internalServerError()
                    .body(ChatResponse.builder()
                            .sessionId(request.getSessionId())
                            .reply("服务暂时不可用，请稍后重试。")
                            .agentMode("error")
                            .build());
        }
    }

    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chatStream(@Valid @RequestBody ChatRequest request) {
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);
        String traceId = TraceContext.ensureTraceId();
        sseExecutor.execute(() -> {
            TraceContext.setTraceId(traceId);
            try {
                agentOrchestrator.processMessageStream(request.getSessionId(), request.getMessage())
                        .publishOn(Schedulers.boundedElastic())
                        //每来一个分片，就通过 SSE 发给前端
                        .doOnNext(chunk -> sendChunk(emitter, chunk))
                        //出现错误
                        .doOnError(err -> {
                            //先记日志
                            log.error("Stream error session={}", request.getSessionId(), err);
                            //再给前端发一个 type=error 的错误块
                            sendChunk(emitter, StreamChunk.builder()
                                    .type("error")
                                    .content(err.getMessage() != null ? err.getMessage() : "stream error")
                                    .traceId(traceId)
                                    .build());
                            //结束SSE
                            emitter.completeWithError(err);
                        })
                        .doOnComplete(emitter::complete)
                        .subscribe();
            } catch (Exception e) {
                log.error("Stream setup failed", e);
                try {
                    emitter.send(SseEmitter.event().name("error").data(StreamChunk.builder()
                            .type("error")
                            .content(e.getMessage())
                            .traceId(traceId)
                            .build()));
                } catch (IOException ignored) {
                    // ignore
                }
                emitter.completeWithError(e);
            } finally {
                //清理 TraceContext，避免线程复用时把旧请求的 trace 信息污染到下一个任务。
                TraceContext.clear();
            }
        });
        //SSE 超时时打日志
        emitter.onTimeout(() -> log.warn("SSE timeout session={}", request.getSessionId()));
        //SSE 完成时打日志
        emitter.onCompletion(() -> log.debug("SSE completed session={}", request.getSessionId()));
        return emitter;
    }

    private void sendChunk(SseEmitter emitter, StreamChunk chunk) {
        try {
            emitter.send(SseEmitter.event().data(chunk, MediaType.APPLICATION_JSON));
        } catch (IOException e) {
            log.warn("SSE send failed: {}", e.getMessage());
            emitter.completeWithError(e);
        }
    }

    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        Map<String, Object> body = new HashMap<>();
        body.put("status", "UP");
        body.put("service", "travel-agent-guide");
        return ResponseEntity.ok(body);
    }
}
