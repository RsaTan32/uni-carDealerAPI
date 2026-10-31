package dev.pisti.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

import dev.pisti.entity.Car;
import dev.pisti.entity.Customer;
import dev.pisti.entity.Reservation;
import dev.pisti.entity.Role;
import dev.pisti.entity.User;

public final class ApiViews {
    private ApiViews() {
    }

    public record UserView(Long id, String email, Role role, boolean enabled, Long customerId,
            LocalDateTime createdAt) {
        public static UserView from(User user) {
            return new UserView(user.id, user.email, user.role, user.enabled,
                    user.customer == null ? null : user.customer.id, user.createdAt);
        }
    }

    public record Token(String accessToken, String tokenType, long expiresIn, UserView user) {
    }

    public record CustomerView(Long id, String firstName, String lastName, String email, String phone,
            LocalDate dateOfBirth, String drivingLicenseNumber, LocalDate drivingLicenseExpiry,
            String address, LocalDateTime createdAt) {
        public static CustomerView from(Customer customer) {
            return new CustomerView(customer.id, customer.firstName, customer.lastName, customer.email,
                    customer.phone, customer.dateOfBirth, customer.drivingLicenseNumber,
                    customer.drivingLicenseExpiry, customer.address, customer.createdAt);
        }
    }

    public record CarView(Long id, Long brandId, String brandName, String model, Integer productionYear,
            String color, String fuelType, String transmission, Integer seatCount, BigDecimal dailyPrice,
            String status, Long currentLocationId) {
        public static CarView from(Car car) {
            return new CarView(car.id, car.brand.id, car.brand.name, car.model, car.productionYear,
                    car.color, car.fuelType, car.transmission, car.seatCount, car.dailyPrice, car.status,
                    car.currentLocation == null ? null : car.currentLocation.id);
        }
    }

    public record ReservationView(Long id, Long customerId, CarView car, Long pickupLocationId,
            Long returnLocationId, LocalDateTime startDate, LocalDateTime endDate, BigDecimal totalPrice,
            String status, LocalDateTime createdAt) {
        public static ReservationView from(Reservation reservation) {
            return new ReservationView(reservation.id, reservation.customer.id, CarView.from(reservation.car),
                    reservation.pickupLocation.id, reservation.returnLocation.id, reservation.startDate,
                    reservation.endDate, reservation.totalPrice, reservation.status, reservation.createdAt);
        }
    }
}