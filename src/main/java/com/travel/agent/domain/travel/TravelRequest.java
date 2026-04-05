package com.travel.agent.domain.travel;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.util.List;

/**
 * 出差申请实体：行程需求与审批上下文。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TravelRequest {

    private String requestId;
    private String employeeId;
    private String department;
    private String purpose;
    private String originCity;
    private String destinationCity;
    private LocalDate departureDate;
    private LocalDate returnDate;
    /** 交通方式偏好：flight / train / mixed */
    private List<String> transportPreferences;
    /** 与差标关联的职级或套餐编码 */
    private String policyTierCode;
    private TravelPolicy appliedPolicy;
    private RequestStatus status;

    public enum RequestStatus {
        DRAFT,
        SUBMITTED,
        APPROVED,
        REJECTED,
        CANCELLED
    }
}
