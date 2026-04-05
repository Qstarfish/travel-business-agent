package com.travel.agent.domain.travel;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.Map;

/**
 * 差标（差旅政策）：按城市等级与职级约束金额与舱位。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TravelPolicy {

    private String policyId;
    private String version;
    private String tierCode;
    /** 城市等级 -> 酒店每晚上限 */
    private Map<String, BigDecimal> hotelCapByCityTier;
    /** 允许的机票舱位代码 */
    private String allowedFlightCabins;
    /** 火车座位等级 */
    private String allowedTrainSeatClasses;
    /** 是否需要提前 N 天预订 */
    private int advanceBookingDays;
    /** 超标时是否允许事后审批 */
    private boolean postApprovalForOverage;
    private String remarks;
}
