package dev.pisti.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

import io.quarkus.arc.impl.Identified;

@Entity
@Table(name = "locations", schema = "public")
public class Location {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(nullable = false, length = 150)
    public String name;

    @Column(nullable = false, length = 100)
    public String city;

    @Column(nullable = false, length = 255)
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
