package dev.pisti.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;

public class ReservationRequest {

    @Positive
    public Long customerId;

    @NotNull
    @Positive
    public Long carId;

    @NotNull
    @Positive
    public Long pickupLocationId;

    @NotNull
    @Positive
    public Long returnLocationId;

    @NotNull
    public LocalDateTime startDate;

    @NotNull
    public LocalDateTime endDate;

    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    public BigDecimal totalPrice;

    @Pattern(regexp = "PENDING|CONFIRMED|CANCELLED|COMPLETED")
    public String status;

    @JsonIgnore
    @AssertTrue(message = "End date must be after start date")
    public boolean isDateRangeValid() {
        return startDate == null || endDate == null || endDate.isAfter(startDate);
    }
}
