package com.travel.agent.domain.travel;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 聚合后的行程安排：航班/火车/酒店片段。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Itinerary {

    private String itineraryId;
    private String travelRequestId;
    private List<Leg> legs;
    private List<HotelStay> hotels;
    private BigDecimal estimatedTotalCny;
    private LocalDateTime lastUpdated;
    private boolean policyCompliant;
    private List<String> complianceNotes;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Leg {
        private String mode;
        private String carrier;
        private String flightOrTrainNo;
        private LocalDateTime departAt;
        private LocalDateTime arriveAt;
        private String fromCity;
        private String toCity;
        private BigDecimal priceCny;
        private String cabinOrSeatClass;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class HotelStay {
        private String city;
        private LocalDateTime checkIn;
        private LocalDateTime checkOut;
        private String hotelName;
        private BigDecimal nightlyRateCny;
        private int starRating;
    }
}
