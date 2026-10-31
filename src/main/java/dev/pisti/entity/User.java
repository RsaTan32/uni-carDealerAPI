package dev.pisti.entity;

import java.time.LocalDateTime;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

@Entity
@Table(name = "app_users", schema = "public")
public class User {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(nullable = false, unique = true, length = 255)
    public String email;

    @JsonIgnore
    @Column(name = "password_hash", nullable = false, length = 100)
    public String passwordHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    public Role role;

    @Column(nullable = false)
    public boolean enabled = true;

    @JsonIgnore
    @Column(name = "token_version", nullable = false)
    public long tokenVersion;

    @OneToOne
    @JoinColumn(name = "customer_id", unique = true)
    public Customer customer;

    @Column(name = "created_at", nullable = false)
    public LocalDateTime createdAt;

    @PrePersist
    public void prePersist() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }
}