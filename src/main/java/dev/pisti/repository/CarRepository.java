package dev.pisti.repository;

import dev.pisti.entity.Car;
import io.quarkus.hibernate.orm.panache.PanacheRepository;
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class CarRepository implements PanacheRepository<Car> {

}
