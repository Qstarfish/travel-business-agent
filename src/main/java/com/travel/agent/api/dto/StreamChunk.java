package com.travel.agent.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StreamChunk {

    /**
     * delta | meta | done | error
     */
    private String type;

    private String content;

    private String agentMode;

    private String traceId;
}
