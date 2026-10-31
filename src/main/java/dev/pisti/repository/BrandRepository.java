package dev.pisti.repository;

import dev.pisti.entity.Brand;
import io.quarkus.hibernate.orm.panache.PanacheRepository;
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class BrandRepository implements PanacheRepository<Brand> {

}
