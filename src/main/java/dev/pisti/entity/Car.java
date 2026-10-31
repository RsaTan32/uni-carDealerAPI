package dev.pisti.entity;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import io.quarkus.arc.impl.Identified;
import jakarta.persistence.*;

@Entity
@Table(name = "cars", schema = "public")
public class Car {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @ManyToOne
    @JoinColumn(name = "brand_id", nullable = false)
    public Brand brand;

    @ManyToOne
    @JoinColumn(name = "current_location_id")
    public Location currentLocation;

    @Column(nullable = false, length = 100)
    public String model;

    @Column(name = "production_year", nullable = false)
    public Integer productionYear;

    @Column(name = "license_plate", nullable = false, unique = true, length = 20)
    public String licensePlate;

    @Column(nullable = false, unique = true, length = 50)
    public String vin;

    @Column(length = 50)
    public String color;

    @Column(name = "fuel_type", nullable = false, length = 30)
    public String fuelType;

    @Column(nullable = false, length = 30)
    public String transmission;

    @Column(name = "seat_count", nullable = false)
    public Integer seatCount;

    @Column(nullable = false)
    public Integer mileage;

    @Column(name = "daily_price", nullable = false, precision = 10, scale = 2)
    public BigDecimal dailyPrice;

    @Column(nullable = false, length = 30)
    public String status;

    @Column(name = "created_at")
    public LocalDateTime createdAt;

    @PrePersist
    public void prePersist() {

        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }

        if (mileage == null) {
            mileage = 0;
        }

        if (status == null) {
            status = "AVAILABLE";
        }
    }

}
