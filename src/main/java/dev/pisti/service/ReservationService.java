package dev.pisti.service;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import dev.pisti.dto.ApiViews.ReservationView;
import dev.pisti.dto.ReservationRequest;
import dev.pisti.entity.Car;
import dev.pisti.entity.Customer;
import dev.pisti.entity.Location;
import dev.pisti.entity.Reservation;
import dev.pisti.repository.ReservationRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.WebApplicationException;

@ApplicationScoped
public class ReservationService {
    private static final Set<String> STATUSES = Set.of("PENDING", "CONFIRMED", "CANCELLED", "COMPLETED");
    private static final Set<String> BLOCKING_STATUSES = Set.of("PENDING", "CONFIRMED");

    @Inject
    ReservationRepository reservations;

    @Inject
    EntityManager entityManager;

    @Inject
    CurrentUser currentUser;

    public List<ReservationView> list() {
        List<Reservation> result = currentUser.isStaff() ? reservations.listAll()
                : reservations.list("customer.id", currentUser.customer().id);
        return result.stream().map(ReservationView::from).toList();
    }

    public Reservation findAccessible(Long id) {
        Reservation reservation = reservations.findById(id);
        if (reservation == null || !currentUser.canAccess(reservation.customer)) {
            throw new NotFoundException();
        }
        return reservation;
    }

    @Transactional
    public ReservationView create(ReservationRequest request) {
        if (request.startDate != null && request.startDate.isBefore(LocalDateTime.now())) {
            throw new BadRequestException("New reservations cannot start in the past");
        }
        Reservation reservation = new Reservation();
        apply(reservation, request);
        reservations.persistAndFlush(reservation);
        return ReservationView.from(reservation);
    }

    @Transactional
    public ReservationView update(Long id, ReservationRequest request) {
        Reservation reservation = findAccessibleForUpdate(id);
        ensureEditable(reservation);
        if (!currentUser.isStaff() && !"PENDING".equals(reservation.status)) {
            throw new WebApplicationException("Only pending reservations can be edited by customers", 409);
        }
        apply(reservation, request);
        reservations.flush();
        return ReservationView.from(reservation);
    }

    @Transactional
    public ReservationView cancel(Long id) {
        Reservation reservation = findAccessibleForUpdate(id);
        verifyTransition(reservation, "CANCELLED", reservation.endDate);
        reservation.status = "CANCELLED";
        reservations.flush();
        return ReservationView.from(reservation);
    }

    @Transactional
    public ReservationView changeStatus(Long id, String status) {
        Reservation reservation = findAccessibleForUpdate(id);
        verifyTransition(reservation, status, reservation.endDate);
        if (BLOCKING_STATUSES.contains(status)) {
            Car car = entityManager.find(Car.class, reservation.car.id, LockModeType.PESSIMISTIC_WRITE);
            ensureBookable(car);
            ensureNoOverlap(reservation.id, car.id, reservation.startDate, reservation.endDate);
        }
        reservation.status = status;
        reservations.flush();
        return ReservationView.from(reservation);
    }

    @Transactional
    public void delete(Long id) {
        Reservation reservation = findAccessibleForUpdate(id);
        reservations.delete(reservation);
    }

    private Reservation findAccessibleForUpdate(Long id) {
        Reservation reservation = reservations.findById(id, LockModeType.PESSIMISTIC_WRITE);
        if (reservation == null || !currentUser.canAccess(reservation.customer)) {
            throw new NotFoundException();
        }
        return reservation;
    }

    private void apply(Reservation reservation, ReservationRequest request) {
        if (request.startDate == null || request.endDate == null || !request.endDate.isAfter(request.startDate)) {
            throw new BadRequestException("End date must be after start date");
        }
        Customer customer;
        if (currentUser.isStaff()) {
            customer = find(Customer.class, request.customerId, "Customer");
        } else {
            customer = currentUser.customer();
            if (request.customerId != null && !request.customerId.equals(customer.id)) {
                throw new ForbiddenException("Cannot reserve for another customer");
            }
        }
        Car car = request.carId == null ? null
                : entityManager.find(Car.class, request.carId, LockModeType.PESSIMISTIC_WRITE);
        if (car == null) {
            throw new BadRequestException("Car not found");
        }
        String status = reservation.status == null ? "PENDING" : reservation.status;
        if (request.status != null) {
            if (reservation.id == null && !"PENDING".equals(request.status)) {
                throw new BadRequestException("New reservations must start in PENDING status");
            }
            if (!currentUser.isStaff() && !status.equals(request.status)) {
                throw new ForbiddenException("Customers must use the cancellation endpoint to change status");
            }
            status = request.status;
        }
        if (!STATUSES.contains(status)) {
            throw new BadRequestException("Invalid reservation status");
        }
        if (reservation.id != null) {
            verifyTransition(reservation, status, request.endDate);
        }
        if (BLOCKING_STATUSES.contains(status)) {
            ensureBookable(car);
            ensureNoOverlap(reservation.id, car.id, request.startDate, request.endDate);
        }
        Location pickupLocation = find(Location.class, request.pickupLocationId, "Pickup location");
        Location returnLocation = find(Location.class, request.returnLocationId, "Return location");

        Duration duration = Duration.between(request.startDate, request.endDate);
        long days = duration.toDays();
        if (!duration.minusDays(days).isZero()) {
            days++;
        }
        if (car.dailyPrice == null || car.dailyPrice.signum() <= 0) {
            throw new WebApplicationException("Car daily price must be positive before booking", 409);
        }
        BigDecimal totalPrice = car.dailyPrice.multiply(BigDecimal.valueOf(days));
        if (totalPrice.compareTo(new BigDecimal("99999999.99")) > 0) {
            throw new BadRequestException("Calculated price exceeds the supported maximum");
        }
        reservation.customer = customer;
        reservation.car = car;
        reservation.pickupLocation = pickupLocation;
        reservation.returnLocation = returnLocation;
        reservation.startDate = request.startDate;
        reservation.endDate = request.endDate;
        reservation.totalPrice = totalPrice;
        reservation.status = status;
    }

    private void ensureNoOverlap(Long reservationId, Long carId, LocalDateTime startDate, LocalDateTime endDate) {
        String query = "select count(reservation) from Reservation reservation where reservation.car.id = :carId"
                + " and reservation.status in :statuses and reservation.startDate < :endDate"
                + " and reservation.endDate > :startDate";
        if (reservationId != null) {
            query += " and reservation.id <> :reservationId";
        }
        var overlaps = entityManager.createQuery(query, Long.class)
                .setParameter("carId", carId).setParameter("statuses", BLOCKING_STATUSES)
                .setParameter("startDate", startDate).setParameter("endDate", endDate);
        if (reservationId != null) {
            overlaps.setParameter("reservationId", reservationId);
        }
        if (overlaps.getSingleResult() > 0) {
            throw new WebApplicationException("Car is already reserved for the requested period", 409);
        }
    }

    private void ensureEditable(Reservation reservation) {
        if (!BLOCKING_STATUSES.contains(reservation.status)) {
            throw new WebApplicationException("Cancelled or completed reservations cannot be edited", 409);
        }
    }

    private void verifyTransition(Reservation reservation, String target, LocalDateTime endDate) {
        ensureEditable(reservation);
        if (reservation.status.equals(target)) {
            return;
        }
        boolean allowed = "PENDING".equals(reservation.status)
                ? Set.of("CONFIRMED", "CANCELLED").contains(target)
                : Set.of("COMPLETED", "CANCELLED").contains(target);
        if (!allowed) {
            throw new WebApplicationException("Invalid reservation status transition", 409);
        }
        if ("COMPLETED".equals(target) && endDate.isAfter(LocalDateTime.now())) {
            throw new WebApplicationException("Reservation cannot be completed before its end date", 409);
        }
    }

    private void ensureBookable(Car car) {
        if (!Set.of("AVAILABLE", "RESERVED").contains(car.status)) {
            throw new WebApplicationException("Car is not available for booking in its current state", 409);
        }
    }

    private <EntityType> EntityType find(Class<EntityType> type, Long id, String label) {
        EntityType entity = id == null ? null : entityManager.find(type, id);
        if (entity == null) {
            throw new BadRequestException(label + " not found");
        }
        return entity;
    }
}