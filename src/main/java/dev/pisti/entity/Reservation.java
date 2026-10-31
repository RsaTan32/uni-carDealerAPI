package dev.pisti.entity;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

@Entity
@Table(name = "reservations", schema = "public")
public class Reservation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @ManyToOne
    @JoinColumn(name = "customer_id", nullable = false)
    public Customer customer;

    @ManyToOne
    @JoinColumn(name = "car_id", nullable = false)
    public Car car;

    @ManyToOne
    @JoinColumn(name = "pickup_location_id", nullable = false)
    public Location pickupLocation;

    @ManyToOne
    @JoinColumn(name = "return_location_id", nullable = false)
    public Location returnLocation;

    @Column(name = "start_date", nullable = false)
    public LocalDateTime startDate;

    @Column(name = "end_date", nullable = false)
    public LocalDateTime endDate;

    @Column(name = "total_price", precision = 10, scale = 2)
    public BigDecimal totalPrice;

    @Column(nullable = false, length = 30)
    public String status;

    @Column(name = "created_at")
    public LocalDateTime createdAt;

    @PrePersist
    public void prePersist() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }

        if (status == null) {
            status = "PENDING";
        }
    }

}
