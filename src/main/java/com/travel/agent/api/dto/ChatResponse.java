package com.travel.agent.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatResponse {

    private String sessionId;
    private String reply;
    private String agentMode;
    private String traceId;
    private List<Map<String, String>> citations;
    private long latencyMs;
}
