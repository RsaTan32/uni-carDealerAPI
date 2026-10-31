package dev.pisti.entity;

import java.time.LocalDate;
import java.time.LocalDateTime;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

@Entity
@Table(name = "customers", schema = "public")
public class Customer {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(name = "first_name", nullable = false, length = 100)
    public String firstName;

    @Column(name = "last_name", nullable = false, length = 100)
    public String lastName;

    @Column(nullable = false, unique = true, length = 255)
    public String email;

    @Column(length = 50)
    public String phone;

    @Column(name = "date_of_birth")
    public LocalDate dateOfBirth;

    @Column(name = "driving_license_number", nullable = false, unique = true, length = 100)
    public String drivingLicenseNumber;

    @Column(name = "driving_license_expiry")
    public LocalDate drivingLicenseExpiry;

    @Column(length = 255)
    public String address;

    @Column(name = "created_at")
    public LocalDateTime createdAt;

    @PrePersist
    public void prePersist() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }
}
