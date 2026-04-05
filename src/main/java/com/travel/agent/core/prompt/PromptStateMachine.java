package com.travel.agent.core.prompt;

import com.travel.agent.core.memory.MemoryManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;

/**
 * 根据会话状态与模式动态拼装系统提示词，形成轻量状态机，避免静态 prompt 无法适应多轮商旅流程。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PromptStateMachine {

    private final MemoryManager memoryManager;

    public enum ConversationState {
        /** 首轮或寒暄 */
        GREETING,
        /** 收集目的地、日期、职级等 */
        COLLECT_REQUIREMENT,
        /** 差标核对与合规结论 */
        POLICY_CHECK,
        /** 预订与比价路径 */
        BOOKING_FLOW,
        /** 报销与发票 */
        EXPENSE_FLOW,
        /** 收尾 */
        CLOSING
    }

    /**
     * 结合近期轮次与用户措辞推断对话状态，用于裁剪系统提示。
     */
    public ConversationState resolveState(String sessionId, String userInput, String mode) {
        String text = userInput == null ? "" : userInput.toLowerCase(Locale.ROOT);
        List<String> recent = memoryManager.getRecentRoles(sessionId);

        if (isLikelyFirstTurn(recent, text)) {
            return ConversationState.GREETING;
        }
        if (text.contains("再见") || text.contains("谢谢") || text.contains("先这样")) {
            return ConversationState.CLOSING;
        }

        return switch (normalizeMode(mode)) {
            case "policy" -> ConversationState.POLICY_CHECK;
            case "booking" -> ConversationState.BOOKING_FLOW;
            case "expense" -> ConversationState.EXPENSE_FLOW;
            case "rag" -> ConversationState.COLLECT_REQUIREMENT;
            default -> inferCollectOrGeneral(recent, text);
        };
    }

    private static String normalizeMode(String mode) {
        return mode == null ? "general" : mode.toLowerCase(Locale.ROOT);
    }

    private boolean isLikelyFirstTurn(List<String> recent, String text) {
        return recent.isEmpty()
                || (recent.size() < 3 && (text.contains("你好") || text.contains("在吗") || text.contains("请问")));
    }

    private ConversationState inferCollectOrGeneral(List<String> recent, String text) {
        if (text.contains("多少") || text.contains("哪天") || text.contains("哪里") || text.contains("几号")) {
            return ConversationState.COLLECT_REQUIREMENT;
        }
        if (recent.size() >= 4) {
            return ConversationState.COLLECT_REQUIREMENT;
        }
        return ConversationState.COLLECT_REQUIREMENT;
    }

    /**
     * 拼装最终系统提示：品牌人设 + 状态侧重 + 模式侧重。
     */
    public String buildSystemPrompt(ConversationState state, String mode, String agentName) {
        String base = """
                你是 %s，专注企业商旅场景：差标合规、预订协助、报销指引。
                回答需简洁、可执行，涉及金额与政策时提醒用户以公司最新制度为准。
                """.formatted(agentName);

        String modeHint = modeHint(normalizeMode(mode));
        String stateHint = stateHint(state);

        String guardrails = """
                
                安全与合规：不编造未提供的票价/酒店实时库存；不代替用户完成支付或提交审批，仅说明步骤。
                """;

        return base + "\n状态侧重：" + stateHint + "\n模式侧重：" + modeHint + guardrails;
    }

    private String modeHint(String mode) {
        return switch (mode) {
            case "policy" -> "优先引用检索到的差标条款，指出审批与超标处理路径。";
            case "booking" -> "关注行程可行性、预算与审批前置条件，避免承诺未授权价格。";
            case "expense" -> "聚焦发票、报销单、对账周期与材料清单。";
            case "rag" -> "以知识检索结果为依据，缺失时明确说明并建议联系管理员。";
            default -> "保持专业友好，引导用户补充目的地、日期、职级等关键信息。";
        };
    }

    private String stateHint(ConversationState state) {
        return switch (state) {
            case GREETING -> "先确认用户意图（出差目的、时间、城市），可简要自我介绍。";
            case COLLECT_REQUIREMENT -> "主动追问缺失要素，一次最多追问 3 点，避免冗长表单感。";
            case POLICY_CHECK -> "逐步核对条款，输出合规结论与可选动作（如申请特批）。";
            case BOOKING_FLOW -> "给出可执行步骤：查询→比价→审批→出票/确认；注明需用户本人确认。";
            case EXPENSE_FLOW -> "列出报销材料与常见驳回原因，提示时效与抬头要求。";
            case CLOSING -> "总结要点并邀请后续咨询。";
        };
    }
}
