package dev.pisti.resource;

import dev.pisti.dto.CarRequest;
import dev.pisti.dto.ApiViews.CarView;
import dev.pisti.entity.Brand;
import dev.pisti.entity.Car;
import dev.pisti.repository.CarRepository;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;
import jakarta.annotation.security.PermitAll;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.transaction.Transactional;
import java.net.URI;
import java.util.List;
import java.time.Year;
import java.util.Locale;
import dev.pisti.entity.Location;

@Path("/api/cars")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed({ "EMPLOYEE", "ADMIN" })
public class CarResource {

    @Inject
    CarRepository carRepository;

    @Inject
    EntityManager entityManager;

    @GET
    @PermitAll
    public List<CarView> getAll() {
        return carRepository.listAll().stream().map(CarView::from).toList();
    }

    @GET
    @Path("/{id}")
    @PermitAll
    public Response getById(@PathParam("id") @Positive Long id) {

        Car car = carRepository.findById(id);

        if (car == null) {
            throw new NotFoundException("Car not found");
        }

        return Response.ok(CarView.from(car)).build();
    }

    @POST
    @Transactional
    @SecurityRequirement(name = "bearerAuth")
    public Response create(@NotNull @Valid CarRequest request) {

        validateProductionYear(request.productionYear);

        Brand brand = entityManager.find(Brand.class, request.brandId);

        if (brand == null) {
            throw new BadRequestException("Brand not found");
        }

        Location location = entityManager.find(Location.class, request.currentLocationId);

        if (location == null) {
            throw new BadRequestException("Location not found");
        }

        Car car = new Car();

        car.brand = brand;
        car.currentLocation = location;

        car.model = request.model.trim();
        car.productionYear = request.productionYear;
        car.licensePlate = request.licensePlate.trim().toUpperCase(Locale.ROOT);
        car.vin = request.vin.trim().toUpperCase(Locale.ROOT);
        car.color = request.color;
        car.fuelType = request.fuelType;
        car.transmission = request.transmission;
        car.seatCount = request.seatCount;
        car.mileage = request.mileage;
        car.dailyPrice = request.dailyPrice;
        car.status = request.status;

        carRepository.persistAndFlush(car);

        return Response.created(URI.create("/api/cars/" + car.id)).entity(car).build();
    }

    @PUT
    @Path("/{id}")
    @Transactional
    @SecurityRequirement(name = "bearerAuth")
    public Response update(@PathParam("id") @Positive Long id, @NotNull @Valid CarRequest request) {

        validateProductionYear(request.productionYear);

        Car car = carRepository.findById(id, LockModeType.PESSIMISTIC_WRITE);

        if (car == null) {
            throw new NotFoundException("Car not found");
        }
        if (request.mileage != null && request.mileage < car.mileage) {
            throw new WebApplicationException("Mileage cannot be lower than the current reading", 409);
        }

        Brand brand = entityManager.find(Brand.class, request.brandId);

        if (brand == null) {
            throw new BadRequestException("Brand not found");
        }

        Location location = entityManager.find(Location.class, request.currentLocationId);

        if (location == null) {
            throw new BadRequestException("Location not found");
        }

        car.brand = brand;
        car.currentLocation = location;

        car.model = request.model.trim();
        car.productionYear = request.productionYear;
        car.licensePlate = request.licensePlate.trim().toUpperCase(Locale.ROOT);
        car.vin = request.vin.trim().toUpperCase(Locale.ROOT);
        car.color = request.color;
        car.fuelType = request.fuelType;
        car.transmission = request.transmission;
        car.seatCount = request.seatCount;
        if (request.mileage != null) {
            car.mileage = request.mileage;
        }
        car.dailyPrice = request.dailyPrice;
        if (request.status != null) {
            car.status = request.status;
        }

        carRepository.flush();
        return Response.ok(car).build();
    }

    @DELETE
    @Path("/{id}")
    @Transactional
    @SecurityRequirement(name = "bearerAuth")
    public Response delete(@PathParam("id") @Positive Long id) {

        boolean deleted = carRepository.deleteById(id);

        if (!deleted) {
            throw new NotFoundException("Car not found");
        }

        carRepository.flush();
        return Response.noContent().build();
    }

    private void validateProductionYear(Integer year) {
        if (year > Year.now().getValue() + 1) {
            throw new BadRequestException("Production year cannot be later than next year");
        }
    }
}
