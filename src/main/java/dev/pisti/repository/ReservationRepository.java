package dev.pisti.repository;

import dev.pisti.entity.Reservation;
import io.quarkus.hibernate.orm.panache.PanacheRepository;
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class ReservationRepository implements PanacheRepository<Reservation> {

}
