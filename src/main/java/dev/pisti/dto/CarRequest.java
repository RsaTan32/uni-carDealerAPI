package dev.pisti.dto;

import java.math.BigDecimal;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

public class CarRequest {

    @NotNull
    @Positive
    public Long brandId;

    @NotBlank
    @Size(max = 100)
    public String model;

    @NotNull
    @Min(1886)
    @Max(9999)
    public Integer productionYear;

    @NotBlank
    @Size(max = 20)
    public String licensePlate;

    @NotBlank
    @Size(max = 50)
    public String vin;

    @Size(max = 50)
    public String color;

    @NotBlank
    @Size(max = 30)
    public String fuelType;

    @NotBlank
    @Size(max = 30)
    public String transmission;

    @NotNull
    @Min(1)
    @Max(9)
    public Integer seatCount;

    @PositiveOrZero
    @JsonSetter(nulls = Nulls.FAIL)
    public Integer mileage;

    @NotNull
    @Positive
    @Digits(integer = 8, fraction = 2)
    public BigDecimal dailyPrice;

    @Pattern(regexp = "AVAILABLE|RESERVED|RENTED|MAINTENANCE|INACTIVE")
    @JsonSetter(nulls = Nulls.FAIL)
    public String status;

    @NotNull
    @Positive
    public Long currentLocationId;
}
