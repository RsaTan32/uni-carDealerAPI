package dev.pisti;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.testcontainers.postgresql.PostgreSQLContainer;

import dev.pisti.entity.Brand;
import dev.pisti.entity.Car;
import dev.pisti.entity.Customer;
import dev.pisti.entity.Location;
import dev.pisti.entity.Reservation;
import dev.pisti.entity.Role;
import dev.pisti.entity.User;
import io.quarkus.elytron.security.common.BcryptUtil;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.smallrye.jwt.build.Jwt;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;

@QuarkusTest
@QuarkusTestResource(PostgresTestResource.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AuthenticationResourceTest {
    private static final String PASSWORD = "test-password-123";
    private static final String PASSWORD_HASH = BcryptUtil.bcryptHash(PASSWORD);

    @Inject
    EntityManager entityManager;

    @Inject
    DataSource dataSource;

    private Long adminId;
    private Long employeeId;
    private Long customerUserId;
    private Long customerId;
    private Long otherCustomerId;
    private Long carId;
    private Long brandId;
    private Long locationId;

    @BeforeAll
    void bootstrapAdminWasCreatedAtStartup() {
        Flyway.configure().dataSource(dataSource).baselineOnMigrate(true).baselineVersion("1")
                .locations("classpath:db/migration").load().migrate();
        QuarkusTransaction.requiringNew().run(() -> {
            User admin = entityManager.createQuery("from User where email = :email", User.class)
                    .setParameter("email", "bootstrap@example.com").getSingleResult();
            assertEquals(Role.ADMIN, admin.role);
            assertTrue(admin.enabled);
            assertTrue(BcryptUtil.matches("bootstrap-test-password", admin.passwordHash));
        });
    }

    @BeforeEach
    void seed() {
        QuarkusTransaction.requiringNew().run(() -> {
            entityManager.createQuery("delete from Reservation").executeUpdate();
            entityManager.createQuery("delete from User").executeUpdate();
            entityManager.createQuery("delete from Customer").executeUpdate();
            entityManager.createQuery("delete from Car").executeUpdate();
            entityManager.createQuery("delete from Brand").executeUpdate();
            entityManager.createQuery("delete from Location").executeUpdate();

            Customer customer = customer("customer@example.com", "LICENSE-1");
            Customer other = customer("other@example.com", "LICENSE-2");
            customerId = customer.id;
            otherCustomerId = other.id;
            adminId = user("admin@example.com", Role.ADMIN, null).id;
            employeeId = user("employee@example.com", Role.EMPLOYEE, null).id;
            customerUserId = user(customer.email, Role.CUSTOMER, customer).id;
            user(other.email, Role.CUSTOMER, other);

            Brand brand = new Brand();
            brand.name = "Test brand";
            entityManager.persist(brand);
            brandId = brand.id;
            Location location = new Location();
            location.name = "Test office";
            location.city = "Budapest";
            location.address = "Test street 1";
            entityManager.persist(location);
            locationId = location.id;

            Car car = new Car();
            car.brand = brand;
            car.currentLocation = location;
            car.model = "Test model";
            car.productionYear = 2024;
            car.licensePlate = "PRIVATE-PLATE";
            car.vin = "PRIVATE-VIN";
            car.fuelType = "PETROL";
            car.transmission = "MANUAL";
            car.seatCount = 5;
            car.dailyPrice = new BigDecimal("10000.00");
            entityManager.persist(car);
            carId = car.id;
        });
    }

    @Test
    void anonymousCanBrowseButCannotAccessPrivateData() {
        given().get("/api/cars").then().statusCode(200)
                .body("size()", is(1)).body("[0].vin", nullValue())
                .body("[0].licensePlate", nullValue());
        given().get("/api/brands").then().statusCode(200);
        given().get("/api/locations").then().statusCode(200);
        for (String path : new String[] { "/api/customers", "/api/reservations", "/api/users", "/api/auth/me" }) {
            given().get(path).then().statusCode(401);
        }
        given().contentType(ContentType.JSON).body(Map.of("name", "Unauthorized"))
                .post("/api/brands").then().statusCode(401);
    }

    @Test
    void healthProbesAreAvailableForKubernetes() {
        given().get("/q/health/live").then().statusCode(200).body("status", is("UP"));
        given().get("/q/health/ready").then().statusCode(200).body("status", is("UP"));
    }

    @Test
    void registrationAlwaysCreatesCustomerAndHashesPassword() {
        Map<String, Object> request = registration("New@Example.com");
        request.put("role", "ADMIN");
        request.put("enabled", false);
        request.put("customerId", otherCustomerId);
        Number id = given().contentType(ContentType.JSON).body(request).post("/api/auth/register")
                .then().statusCode(201).body("role", is("CUSTOMER")).body("enabled", is(true))
                .body("email", is("new@example.com")).body("passwordHash", nullValue())
                .body("password", nullValue()).extract().path("id");
        QuarkusTransaction.requiringNew().run(() -> {
            User account = entityManager.find(User.class, id.longValue());
            assertNotEquals(PASSWORD, account.passwordHash);
            assertTrue(BcryptUtil.matches(PASSWORD, account.passwordHash));
            assertNotEquals(otherCustomerId, account.customer.id);
        });
        given().auth().oauth2(login("new@example.com")).get("/api/auth/me")
                .then().statusCode(200).body("role", is("CUSTOMER"));
    }

    @Test
    void invalidRegistrationAndDuplicateAccountsAreRejected() {
        Map<String, Object> request = registration("new@example.com");
        request.put("password", "short");
        given().contentType(ContentType.JSON).body(request).post("/api/auth/register").then().statusCode(400);
        request.put("password", "\u00e1".repeat(40));
        given().contentType(ContentType.JSON).body(request).post("/api/auth/register").then().statusCode(400);
        given().contentType(ContentType.JSON).body("null").post("/api/auth/register").then().statusCode(400);
        given().contentType(ContentType.JSON).body(registration("CUSTOMER@example.com"))
                .post("/api/auth/register").then().statusCode(409);
    }

    @Test
    void wrongPasswordAndUnknownAccountHaveSameResponse() {
        for (String email : new String[] { "customer@example.com", "unknown@example.com" }) {
            given().contentType(ContentType.JSON).body(Map.of("email", email, "password", "wrong-password"))
                    .post("/api/auth/login").then().statusCode(401)
                    .body("message", is("Invalid email or password"));
        }
    }

    @Test
    void customerCannotManageFleetOrUsers() {
        String token = login("customer@example.com");
        given().auth().oauth2(token).get("/api/users").then().statusCode(403);
        given().auth().oauth2(token).get("/api/customers").then().statusCode(403);
        for (String path : new String[] { "/api/cars", "/api/brands", "/api/locations", "/api/customers",
                "/api/users" }) {
            given().auth().oauth2(token).contentType(ContentType.JSON).body("{}")
                    .post(path).then().statusCode(403);
        }
        given().auth().oauth2(token).delete("/api/cars/" + carId).then().statusCode(403);
    }

    @Test
    void customerCanOnlyReadAndUpdateOwnProfile() {
        String token = login("customer@example.com");
        given().auth().oauth2(token).get("/api/customers/me").then().statusCode(200)
                .body("id", is(customerId.intValue()));
        given().auth().oauth2(token).get("/api/customers/" + otherCustomerId).then().statusCode(404);
        Map<String, Object> update = new HashMap<>(Map.of("firstName", "Updated", "lastName", "Customer",
                "email", "customer@example.com", "drivingLicenseNumber", "LICENSE-1", "role", "ADMIN"));
        given().auth().oauth2(token).contentType(ContentType.JSON).body(update)
                .put("/api/customers/" + otherCustomerId).then().statusCode(404);
        given().auth().oauth2(token).contentType(ContentType.JSON).body(update)
                .put("/api/customers/" + customerId).then().statusCode(200).body("firstName", is("Updated"));
        given().auth().oauth2(token).get("/api/auth/me").then().statusCode(200).body("role", is("CUSTOMER"));
        given().auth().oauth2(token).delete("/api/customers/" + customerId).then().statusCode(403);
    }

    @Test
    void customerReservationsUseOwnIdentityAndServerPriceAndStatus() {
        String token = login("customer@example.com");
        Map<String, Object> request = reservation();
        request.put("totalPrice", -1);
        request.put("status", "PENDING");
        Number id = given().auth().oauth2(token).contentType(ContentType.JSON).body(request)
                .post("/api/reservations").then().statusCode(201)
                .body("customerId", is(customerId.intValue())).body("totalPrice", comparesEqualTo(20000.0f))
                .body("status", is("PENDING")).body("customer", nullValue()).extract().path("id");
        request.put("customerId", otherCustomerId);
        given().auth().oauth2(token).contentType(ContentType.JSON).body(request)
                .post("/api/reservations").then().statusCode(403);
        given().auth().oauth2(token).contentType(ContentType.JSON).body(request)
                .put("/api/reservations/" + id).then().statusCode(403);
        request.remove("customerId");
        given().auth().oauth2(token).contentType(ContentType.JSON).body(request)
                .put("/api/reservations/" + id).then().statusCode(200).body("status", is("PENDING"));
        given().auth().oauth2(token).delete("/api/reservations/" + id).then().statusCode(403);
        given().auth().oauth2(token).post("/api/reservations/" + id + "/cancel")
                .then().statusCode(200).body("status", is("CANCELLED"));
        given().auth().oauth2(token).contentType(ContentType.JSON).body(request)
                .put("/api/reservations/" + id).then().statusCode(409);
    }

    @Test
    void otherCustomerCannotReadEditCancelOrListForeignReservations() {
        String ownerToken = login("customer@example.com");
        Number id = given().auth().oauth2(ownerToken).contentType(ContentType.JSON).body(reservation())
                .post("/api/reservations").then().statusCode(201).extract().path("id");
        String otherToken = login("other@example.com");
        given().auth().oauth2(otherToken).get("/api/reservations").then().statusCode(200).body("size()", is(0));
        given().auth().oauth2(ownerToken).get("/api/reservations").then().statusCode(200).body("size()", is(1));
        given().auth().oauth2(otherToken).get("/api/reservations/" + id).then().statusCode(404);
        given().auth().oauth2(otherToken).contentType(ContentType.JSON).body(reservation())
                .put("/api/reservations/" + id).then().statusCode(404);
        given().auth().oauth2(otherToken).post("/api/reservations/" + id + "/cancel").then().statusCode(404);
    }

    @Test
    void validationAndHttpErrorsUseConsistentJson() {
        String employee = login("employee@example.com");
        given().auth().oauth2(employee).contentType(ContentType.JSON).body(Map.of("name", " "))
                .post("/api/brands").then().statusCode(400).contentType(ContentType.JSON)
                .body("status", is(400)).body("code", is("VALIDATION_ERROR"))
                .body("errors.name", not(emptyOrNullString()));
        given().get("/api/cars/999999").then().statusCode(404).contentType(ContentType.JSON)
                .body("status", is(404)).body("code", is("NOT_FOUND")).body("errors", anEmptyMap());
        given().get("/api/auth/me").then().statusCode(401).contentType(ContentType.JSON)
                .body("status", is(401)).body("code", is("UNAUTHORIZED"));
        given().auth().oauth2(login("customer@example.com")).get("/api/users")
                .then().statusCode(403).contentType(ContentType.JSON).body("code", is("FORBIDDEN"));
        given().auth().oauth2(employee).contentType(ContentType.JSON).body("{")
                .post("/api/brands").then().statusCode(400).contentType(ContentType.JSON)
                .body("status", is(400)).body("code", is("BAD_REQUEST"));
    }

    @Test
    void duplicateBrandsReturnConflictWithoutDatabaseDetails() {
        String employee = login("employee@example.com");
        String response = given().auth().oauth2(employee).contentType(ContentType.JSON)
                .body(Map.of("name", "Test brand")).post("/api/brands")
                .then().statusCode(409).contentType(ContentType.JSON).body("status", is(409))
                .body("code", is("DUPLICATE_RESOURCE")).extract().asString();
        assertFalse(response.contains("SQL"));
        assertFalse(response.contains("org.hibernate"));
        assertFalse(response.contains("brands_name"));
    }

    @Test
    void invalidCrudPayloadsAreRejectedBeforePersistence() {
        String employee = login("employee@example.com");
        for (String endpoint : new String[] { "/api/cars", "/api/brands", "/api/locations", "/api/customers" }) {
            given().auth().oauth2(employee).contentType(ContentType.JSON).body("{}")
                    .post(endpoint).then().statusCode(400);
            given().auth().oauth2(employee).contentType(ContentType.JSON).body("null")
                    .post(endpoint).then().statusCode(400);
        }
        given().auth().oauth2(employee).contentType(ContentType.JSON).body(Map.of("name", "   "))
                .post("/api/brands").then().statusCode(400);
        Map<String, Object> customer = registration("invalid-email");
        given().auth().oauth2(employee).contentType(ContentType.JSON).body(customer)
                .post("/api/customers").then().statusCode(400);
        Map<String, Object> request = reservation();
        request.put("endDate", request.get("startDate"));
        given().auth().oauth2(login("customer@example.com")).contentType(ContentType.JSON).body(request)
                .post("/api/reservations").then().statusCode(400);
    }

    @Test
    void reservationStatusTransitionsAreRestricted() {
        String customer = login("customer@example.com");
        String employee = login("employee@example.com");
        Number id = given().auth().oauth2(customer).contentType(ContentType.JSON).body(reservation())
                .post("/api/reservations").then().statusCode(201).extract().path("id");
        given().auth().oauth2(customer).contentType(ContentType.JSON).body(Map.of("status", "CONFIRMED"))
                .patch("/api/reservations/" + id + "/status").then().statusCode(403);
        given().auth().oauth2(employee).contentType(ContentType.JSON).body(Map.of("status", "COMPLETED"))
                .patch("/api/reservations/" + id + "/status").then().statusCode(409);
        given().auth().oauth2(employee).contentType(ContentType.JSON).body(Map.of("status", "CONFIRMED"))
                .patch("/api/reservations/" + id + "/status").then().statusCode(200)
                .body("status", is("CONFIRMED")).body("totalPrice", comparesEqualTo(20000.0f));
        Map<String, Object> update = reservation();
        update.put("customerId", customerId);
        update.put("totalPrice", 1);
        given().auth().oauth2(employee).contentType(ContentType.JSON).body(update)
                .put("/api/reservations/" + id).then().statusCode(200)
                .body("status", is("CONFIRMED")).body("totalPrice", comparesEqualTo(20000.0f));
        given().auth().oauth2(employee).contentType(ContentType.JSON).body(Map.of("status", "PENDING"))
                .patch("/api/reservations/" + id + "/status").then().statusCode(409);
        given().auth().oauth2(employee).contentType(ContentType.JSON).body(Map.of("status", "COMPLETED"))
                .patch("/api/reservations/" + id + "/status").then().statusCode(409);
        given().auth().oauth2(customer).post("/api/reservations/" + id + "/cancel").then().statusCode(200);
        given().auth().oauth2(employee).contentType(ContentType.JSON).body(Map.of("status", "CONFIRMED"))
                .patch("/api/reservations/" + id + "/status").then().statusCode(409);
    }

    @Test
    void staffCannotOverrideCalculatedReservationPrice() {
        String token = login("employee@example.com");
        Map<String, Object> request = reservation();
        request.put("customerId", customerId);
        request.put("totalPrice", -1);
        given().auth().oauth2(token).contentType(ContentType.JSON).body(request)
                .post("/api/reservations").then().statusCode(201)
                .body("totalPrice", comparesEqualTo(20000.0f)).body("status", is("PENDING"));
    }

    @Test
    void overlappingReservationsAreRejected() {
        String token = login("customer@example.com");
        given().auth().oauth2(token).contentType(ContentType.JSON).body(reservation())
                .post("/api/reservations").then().statusCode(201);
        for (String[] interval : new String[][] {
                { "2027-01-01T10:00:00", "2027-01-03T10:00:00" },
                { "2026-12-31T10:00:00", "2027-01-02T10:00:00" },
                { "2027-01-02T10:00:00", "2027-01-04T10:00:00" },
                { "2027-01-02T10:00:00", "2027-01-02T11:00:00" },
                { "2026-12-31T10:00:00", "2027-01-04T10:00:00" } }) {
            Map<String, Object> request = reservation();
            request.put("startDate", interval[0]);
            request.put("endDate", interval[1]);
            given().auth().oauth2(token).contentType(ContentType.JSON).body(request)
                    .post("/api/reservations").then().statusCode(409)
                    .body("message", is("Car is already reserved for the requested period"));
        }
    }

    @Test
    void adjacentBookingsAndDifferentCarsDoNotConflict() {
        String token = login("customer@example.com");
        given().auth().oauth2(token).contentType(ContentType.JSON).body(reservation())
                .post("/api/reservations").then().statusCode(201);
        for (String[] interval : new String[][] {
                { "2026-12-31T10:00:00", "2027-01-01T10:00:00" },
                { "2027-01-03T10:00:00", "2027-01-04T10:00:00" } }) {
            Map<String, Object> request = reservation();
            request.put("startDate", interval[0]);
            request.put("endDate", interval[1]);
            given().auth().oauth2(token).contentType(ContentType.JSON).body(request)
                    .post("/api/reservations").then().statusCode(201);
        }
        Long anotherCarId = anotherCar();
        Map<String, Object> request = reservation();
        request.put("carId", anotherCarId);
        given().auth().oauth2(token).contentType(ContentType.JSON).body(request)
                .post("/api/reservations").then().statusCode(201);
    }

    @Test
    void updatingDatesOrCarRejectsConflictsAndKeepsOriginalBooking() {
        String token = login("customer@example.com");
        Number firstId = given().auth().oauth2(token).contentType(ContentType.JSON).body(reservation())
                .post("/api/reservations").then().statusCode(201).extract().path("id");
        given().auth().oauth2(token).contentType(ContentType.JSON).body(reservation())
                .put("/api/reservations/" + firstId).then().statusCode(200);
        Map<String, Object> later = reservation();
        later.put("startDate", "2027-01-03T10:00:00");
        later.put("endDate", "2027-01-05T10:00:00");
        Number laterId = given().auth().oauth2(token).contentType(ContentType.JSON).body(later)
                .post("/api/reservations").then().statusCode(201).extract().path("id");
        given().auth().oauth2(token).contentType(ContentType.JSON).body(reservation())
                .put("/api/reservations/" + laterId).then().statusCode(409);
        given().auth().oauth2(token).get("/api/reservations/" + laterId).then().statusCode(200)
                .body("startDate", is("2027-01-03T10:00:00"));

        Map<String, Object> another = reservation();
        Long anotherCarId = anotherCar();
        another.put("carId", anotherCarId);
        Number anotherId = given().auth().oauth2(token).contentType(ContentType.JSON).body(another)
                .post("/api/reservations").then().statusCode(201).extract().path("id");
        given().auth().oauth2(token).contentType(ContentType.JSON).body(reservation())
                .put("/api/reservations/" + anotherId).then().statusCode(409);
        given().auth().oauth2(token).get("/api/reservations/" + anotherId).then().statusCode(200)
                .body("car.id", is(anotherCarId.intValue()));
    }

    @Test
    void cancelledAndCompletedBookingsDoNotBlockAndCannotBeReactivated() {
        String employee = login("employee@example.com");
        LocalDateTime start = LocalDateTime.parse("2027-01-01T10:00:00");
        LocalDateTime end = LocalDateTime.parse("2027-01-03T10:00:00");
        Long cancelledId = storedReservation("CANCELLED", start, end);
        Long completedId = storedReservation("COMPLETED", start, end);
        String customer = login("customer@example.com");
        Number activeId = given().auth().oauth2(customer).contentType(ContentType.JSON).body(reservation())
                .post("/api/reservations").then().statusCode(201).extract().path("id");
        Map<String, Object> request = reservation();
        request.put("customerId", customerId);
        request.put("status", "CONFIRMED");
        for (Long id : new Long[] { cancelledId, completedId }) {
            given().auth().oauth2(employee).contentType(ContentType.JSON).body(request)
                    .put("/api/reservations/" + id).then().statusCode(409);
            given().auth().oauth2(employee).contentType(ContentType.JSON).body(Map.of("status", "CONFIRMED"))
                    .patch("/api/reservations/" + id + "/status").then().statusCode(409);
        }
        given().auth().oauth2(customer).post("/api/reservations/" + activeId + "/cancel")
                .then().statusCode(200);
        given().auth().oauth2(customer).contentType(ContentType.JSON).body(reservation())
                .post("/api/reservations").then().statusCode(201);
    }

    @Test
    void concurrentRequestsCannotBookSameCarTwice() throws Exception {
        String firstToken = login("customer@example.com");
        String secondToken = login("other@example.com");
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> {
                ready.countDown();
                assertTrue(start.await(10, TimeUnit.SECONDS));
                return given().auth().oauth2(firstToken).contentType(ContentType.JSON).body(reservation())
                        .post("/api/reservations").statusCode();
            });
            var second = executor.submit(() -> {
                ready.countDown();
                assertTrue(start.await(10, TimeUnit.SECONDS));
                return given().auth().oauth2(secondToken).contentType(ContentType.JSON).body(reservation())
                        .post("/api/reservations").statusCode();
            });
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            assertEquals(Set.of(201, 409), Set.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS)));
            given().auth().oauth2(login("employee@example.com")).get("/api/reservations")
                    .then().statusCode(200).body("size()", is(1));
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void concurrentUpdatesCannotMoveTwoBookingsIntoSamePeriod() throws Exception {
        String token = login("customer@example.com");
        Number firstId = given().auth().oauth2(token).contentType(ContentType.JSON).body(reservation())
                .post("/api/reservations").then().statusCode(201).extract().path("id");
        Map<String, Object> later = reservation();
        later.put("startDate", "2027-01-03T10:00:00");
        later.put("endDate", "2027-01-05T10:00:00");
        Number secondId = given().auth().oauth2(token).contentType(ContentType.JSON).body(later)
                .post("/api/reservations").then().statusCode(201).extract().path("id");
        Map<String, Object> target = reservation();
        target.put("startDate", "2027-01-10T10:00:00");
        target.put("endDate", "2027-01-12T10:00:00");
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> {
                ready.countDown();
                assertTrue(start.await(10, TimeUnit.SECONDS));
                return given().auth().oauth2(token).contentType(ContentType.JSON).body(target)
                        .put("/api/reservations/" + firstId).statusCode();
            });
            var second = executor.submit(() -> {
                ready.countDown();
                assertTrue(start.await(10, TimeUnit.SECONDS));
                return given().auth().oauth2(token).contentType(ContentType.JSON).body(target)
                        .put("/api/reservations/" + secondId).statusCode();
            });
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            assertEquals(Set.of(200, 409), Set.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS)));
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void concurrentDirectDatabaseWritesAreRejectedWithoutServiceLocks() throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> insertDirectBooking(ready, start));
            var second = executor.submit(() -> insertDirectBooking(ready, start));
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            String firstResult = first.get(20, TimeUnit.SECONDS);
            String secondResult = second.get(20, TimeUnit.SECONDS);
            assertTrue("created".equals(firstResult) ^ "created".equals(secondResult));
            String rejected = "created".equals(firstResult) ? secondResult : firstResult;
            assertTrue(Set.of("23P01", "40P01").contains(rejected));
            try (var connection = dataSource.getConnection();
                    var statement = connection.createStatement();
                    var rows = statement.executeQuery("SELECT count(*) FROM public.reservations")) {
                assertTrue(rows.next());
                assertEquals(1, rows.getLong(1));
            }
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void employeeCanManageRecordsButCannotManageAccounts() {
        String token = login("employee@example.com");
        given().auth().oauth2(token).contentType(ContentType.JSON).body(Map.of("name", "Employee brand"))
                .post("/api/brands").then().statusCode(201);
        given().auth().oauth2(token).get("/api/customers").then().statusCode(200).body("size()", is(2));
        given().auth().oauth2(token).get("/api/users").then().statusCode(403);
        Map<String, Object> request = reservation();
        request.put("customerId", otherCustomerId);
        request.put("status", "PENDING");
        given().auth().oauth2(token).contentType(ContentType.JSON).body(request)
                .post("/api/reservations").then().statusCode(201)
                .body("customerId", is(otherCustomerId.intValue())).body("status", is("PENDING"));
    }

    @Test
    void carValidationRejectsInvalidNumbersAndPreservesStoredData() {
        String employee = login("employee@example.com");
        Map<String, Object> invalidValues = Map.ofEntries(
                Map.entry("model", " "), Map.entry("productionYear", 1800),
                Map.entry("licensePlate", "A".repeat(21)), Map.entry("vin", "V".repeat(51)),
                Map.entry("fuelType", " "), Map.entry("transmission", " "),
                Map.entry("seatCount", 0), Map.entry("mileage", -1), Map.entry("dailyPrice", 0),
                Map.entry("status", "UNKNOWN"), Map.entry("brandId", -1), Map.entry("currentLocationId", 0));
        for (var entry : invalidValues.entrySet()) {
            Map<String, Object> payload = carPayload();
            payload.put(entry.getKey(), entry.getValue());
            given().auth().oauth2(employee).contentType(ContentType.JSON).body(payload)
                    .post("/api/cars").then().statusCode(400).body("code", is("VALIDATION_ERROR"))
                    .body("errors." + entry.getKey(), not(emptyOrNullString()));
            given().auth().oauth2(employee).contentType(ContentType.JSON).body(payload)
                    .put("/api/cars/" + carId).then().statusCode(400);
        }
        for (Object value : new Object[] { -1, new BigDecimal("0.001"), new BigDecimal("100000000") }) {
            Map<String, Object> payload = carPayload();
            payload.put("dailyPrice", value);
            given().auth().oauth2(employee).contentType(ContentType.JSON).body(payload)
                    .post("/api/cars").then().statusCode(400).body("errors.dailyPrice", not(emptyOrNullString()));
        }
        Map<String, Object> payload = carPayload();
        payload.put("seatCount", 5.5);
        given().auth().oauth2(employee).contentType(ContentType.JSON).body(payload)
                .post("/api/cars").then().statusCode(400).body("code", is("BAD_REQUEST"));
        payload.put("seatCount", 10);
        given().auth().oauth2(employee).contentType(ContentType.JSON).body(payload)
                .post("/api/cars").then().statusCode(400);
        payload.put("seatCount", 5);
        payload.put("productionYear", 9999);
        given().auth().oauth2(employee).contentType(ContentType.JSON).body(payload)
                .post("/api/cars").then().statusCode(400);
        given().get("/api/cars").then().statusCode(200).body("size()", is(1));
        given().get("/api/cars/" + carId).then().statusCode(200)
                .body("model", is("Test model")).body("dailyPrice", comparesEqualTo(10000.0f));
    }

    @Test
    void omittedCarMileageAndStatusPreserveExistingValues() {
        QuarkusTransaction.requiringNew().run(() -> {
            Car car = entityManager.find(Car.class, carId);
            car.mileage = 100000;
            car.status = "MAINTENANCE";
        });
        Map<String, Object> payload = carPayload();
        payload.remove("mileage");
        payload.remove("status");
        given().auth().oauth2(login("employee@example.com")).contentType(ContentType.JSON).body(payload)
                .put("/api/cars/" + carId).then().statusCode(200)
                .body("mileage", is(100000)).body("status", is("MAINTENANCE"));
    }

    @Test
    void carMileageCannotDecrease() {
        QuarkusTransaction.requiringNew().run(() -> entityManager.find(Car.class, carId).mileage = 100000);
        String employee = login("employee@example.com");
        Map<String, Object> payload = carPayload();
        payload.put("mileage", 99999);
        given().auth().oauth2(employee).contentType(ContentType.JSON).body(payload)
                .put("/api/cars/" + carId).then().statusCode(409);
        QuarkusTransaction.requiringNew().run(() -> {
            Car car = entityManager.find(Car.class, carId);
            assertEquals(100000, car.mileage);
            assertEquals("Test model", car.model);
        });
        payload.put("mileage", 100000);
        given().auth().oauth2(employee).contentType(ContentType.JSON).body(payload)
                .put("/api/cars/" + carId).then().statusCode(200).body("mileage", is(100000));
        payload.put("mileage", 100001);
        given().auth().oauth2(employee).contentType(ContentType.JSON).body(payload)
                .put("/api/cars/" + carId).then().statusCode(200).body("mileage", is(100001));
    }

    @Test
    void newReservationsCannotStartInThePast() {
        Map<String, Object> request = reservation();
        request.put("startDate", LocalDateTime.now().minusDays(2).toString());
        request.put("endDate", LocalDateTime.now().minusDays(1).toString());
        given().auth().oauth2(login("customer@example.com")).contentType(ContentType.JSON).body(request)
                .post("/api/reservations").then().statusCode(400);
        request.put("endDate", LocalDateTime.now().plusDays(1).toString());
        given().auth().oauth2(login("customer@example.com")).contentType(ContentType.JSON).body(request)
                .post("/api/reservations").then().statusCode(400);
        request.put("customerId", customerId);
        given().auth().oauth2(login("employee@example.com")).contentType(ContentType.JSON).body(request)
                .post("/api/reservations").then().statusCode(400);
        given().auth().oauth2(login("employee@example.com")).get("/api/reservations")
                .then().statusCode(200).body("size()", is(0));
    }

    @Test
    void carCreationDefaultsRemainAndExplicitNullsAreRejected() {
        String employee = login("employee@example.com");
        Map<String, Object> payload = carPayload();
        payload.remove("mileage");
        payload.remove("status");
        given().auth().oauth2(employee).contentType(ContentType.JSON).body(payload)
                .post("/api/cars").then().statusCode(201)
                .body("mileage", is(0)).body("status", is("AVAILABLE"));
        for (String field : new String[] { "mileage", "status" }) {
            payload = carPayload();
            payload.put(field, null);
            given().auth().oauth2(employee).contentType(ContentType.JSON).body(payload)
                    .post("/api/cars").then().statusCode(400);
            given().auth().oauth2(employee).contentType(ContentType.JSON).body(payload)
                    .put("/api/cars/" + carId).then().statusCode(400);
        }
    }

    @Test
    void existingPastReservationsRemainAdministrable() {
        LocalDateTime start = LocalDateTime.now().minusDays(3);
        LocalDateTime end = LocalDateTime.now().minusDays(1);
        Long id = storedReservation("PENDING", start, end);
        String employee = login("employee@example.com");
        Map<String, Object> request = reservation();
        request.put("customerId", customerId);
        request.put("startDate", start.toString());
        request.put("endDate", end.toString());
        request.put("status", "CONFIRMED");
        given().auth().oauth2(employee).contentType(ContentType.JSON).body(request)
                .put("/api/reservations/" + id).then().statusCode(200).body("status", is("CONFIRMED"));
        given().auth().oauth2(employee).contentType(ContentType.JSON).body(Map.of("status", "COMPLETED"))
                .patch("/api/reservations/" + id + "/status").then().statusCode(200).body("status", is("COMPLETED"));
    }

    @Test
    void concurrentMileageUpdatesCannotRollBackTheReading() throws Exception {
        String employee = login("employee@example.com");
        Map<String, Object> smaller = carPayload();
        smaller.put("mileage", 100000);
        Map<String, Object> larger = carPayload();
        larger.put("mileage", 200000);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> {
                ready.countDown();
                assertTrue(start.await(10, TimeUnit.SECONDS));
                return given().auth().oauth2(employee).contentType(ContentType.JSON).body(smaller)
                        .put("/api/cars/" + carId).statusCode();
            });
            var second = executor.submit(() -> {
                ready.countDown();
                assertTrue(start.await(10, TimeUnit.SECONDS));
                return given().auth().oauth2(employee).contentType(ContentType.JSON).body(larger)
                        .put("/api/cars/" + carId).statusCode();
            });
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            assertTrue(Set.of(200, 409).contains(first.get(20, TimeUnit.SECONDS)));
            assertEquals(200, second.get(20, TimeUnit.SECONDS));
            QuarkusTransaction.requiringNew()
                    .run(() -> assertEquals(200000, entityManager.find(Car.class, carId).mileage));
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void duplicatePlatesVinsEmailsAndReferencedRecordsReturnConflicts() {
        String employee = login("employee@example.com");
        for (var duplicate : Map.of("licensePlate", "PRIVATE-PLATE", "vin", "PRIVATE-VIN").entrySet()) {
            Map<String, Object> payload = carPayload();
            payload.put(duplicate.getKey(), duplicate.getValue());
            given().auth().oauth2(employee).contentType(ContentType.JSON).body(payload)
                    .post("/api/cars").then().statusCode(409).body("code", is("DUPLICATE_RESOURCE"));
        }
        given().auth().oauth2(employee).contentType(ContentType.JSON).body(registration("customer@example.com"))
                .post("/api/customers").then().statusCode(409).body("code", is("DUPLICATE_RESOURCE"));
        Map<String, Object> customer = registration("new@example.com");
        customer.put("drivingLicenseNumber", "LICENSE-1");
        given().auth().oauth2(employee).contentType(ContentType.JSON).body(customer)
                .post("/api/customers").then().statusCode(409).body("code", is("DUPLICATE_RESOURCE"));
        given().auth().oauth2(employee).delete("/api/brands/" + brandId).then().statusCode(409)
                .body("code", is("REFERENCED_RESOURCE"));
        given().get("/api/brands/" + brandId).then().statusCode(200);
    }

    @Test
    void duplicateUpdatesAreRolledBack() {
        String employee = login("employee@example.com");
        Long another = anotherCar();
        Map<String, Object> payload = carPayload();
        payload.put("licensePlate", "PRIVATE-PLATE");
        given().auth().oauth2(employee).contentType(ContentType.JSON).body(payload)
                .put("/api/cars/" + another).then().statusCode(409).body("code", is("DUPLICATE_RESOURCE"));
        given().get("/api/cars/" + another).then().statusCode(200).body("model", is("Test model"));
        Map<String, Object> customer = registration("other@example.com");
        customer.put("drivingLicenseNumber", "LICENSE-1");
        customer.put("firstName", "Must not persist");
        given().auth().oauth2(employee).contentType(ContentType.JSON).body(customer)
                .put("/api/customers/" + customerId).then().statusCode(409);
        given().auth().oauth2(employee).get("/api/customers/" + customerId).then().statusCode(200)
                .body("firstName", is("Test")).body("email", is("customer@example.com"));
    }

    @Test
    void pricesUseStartedDaysAndRecalculateOnUpdate() {
        String employee = login("employee@example.com");
        LocalDateTime start = LocalDateTime.parse("2027-01-01T10:00:00");
        for (long seconds : new long[] { 60, 86400, 86401, 172800 }) {
            Map<String, Object> request = reservation();
            request.put("customerId", customerId);
            request.put("endDate", start.plusSeconds(seconds).toString());
            request.put("totalPrice", "ignored-value");
            float expected = seconds <= 86400 ? 10000.0f : 20000.0f;
            Number id = given().auth().oauth2(employee).contentType(ContentType.JSON).body(request)
                    .post("/api/reservations").then().statusCode(201)
                    .body("totalPrice", comparesEqualTo(expected)).extract().path("id");
            request.put("endDate", start.plusDays(3).toString());
            given().auth().oauth2(employee).contentType(ContentType.JSON).body(request)
                    .put("/api/reservations/" + id).then().statusCode(200)
                    .body("totalPrice", comparesEqualTo(30000.0f));
            given().auth().oauth2(employee).post("/api/reservations/" + id + "/cancel").then().statusCode(200);
        }
    }

    @Test
    void newBookingsAndTerminalStatesCannotBypassWorkflow() {
        String employee = login("employee@example.com");
        for (String status : new String[] { "CONFIRMED", "COMPLETED", "CANCELLED", "UNKNOWN" }) {
            Map<String, Object> request = reservation();
            request.put("customerId", customerId);
            request.put("status", status);
            given().auth().oauth2(employee).contentType(ContentType.JSON).body(request)
                    .post("/api/reservations").then().statusCode(400);
        }
        Long id = storedReservation("CONFIRMED", LocalDateTime.now().minusDays(3), LocalDateTime.now().minusDays(1));
        given().auth().oauth2(employee).contentType(ContentType.JSON).body(Map.of("status", "COMPLETED"))
                .patch("/api/reservations/" + id + "/status").then().statusCode(200).body("status", is("COMPLETED"));
        given().auth().oauth2(employee).post("/api/reservations/" + id + "/cancel").then().statusCode(409);
        given().auth().oauth2(employee).contentType(ContentType.JSON).body(Map.of("status", "PENDING"))
                .patch("/api/reservations/" + id + "/status").then().statusCode(409);
    }

    @Test
    void unavailableCarsAndInvalidStoredPricesCannotBeBooked() {
        String customer = login("customer@example.com");
        for (String status : new String[] { "MAINTENANCE", "INACTIVE", "RENTED" }) {
            QuarkusTransaction.requiringNew().run(() -> entityManager.find(Car.class, carId).status = status);
            given().auth().oauth2(customer).contentType(ContentType.JSON).body(reservation())
                    .post("/api/reservations").then().statusCode(409);
        }
        QuarkusTransaction.requiringNew().run(() -> {
            Car car = entityManager.find(Car.class, carId);
            car.status = "AVAILABLE";
            car.dailyPrice = BigDecimal.ZERO;
        });
        given().auth().oauth2(customer).contentType(ContentType.JSON).body(reservation())
                .post("/api/reservations").then().statusCode(409);
        QuarkusTransaction.requiringNew()
                .run(() -> entityManager.find(Car.class, carId).dailyPrice = new BigDecimal("99999999.99"));
        given().auth().oauth2(customer).contentType(ContentType.JSON).body(reservation())
                .post("/api/reservations").then().statusCode(400);
    }

    @Test
    void fieldAndPathValidationDoesNotEchoSensitiveInput() {
        String employee = login("employee@example.com");
        Map<String, Object> request = registration("not-an-email");
        request.put("password", "sensitive-test-value");
        String response = given().contentType(ContentType.JSON).body(request).post("/api/auth/register")
                .then().statusCode(400).body("errors.email", not(emptyOrNullString())).extract().asString();
        assertFalse(response.contains("sensitive-test-value"));
        assertFalse(response.contains("not-an-email"));
        given().get("/api/cars/0").then().statusCode(400).body("code", is("VALIDATION_ERROR"));
        given().auth().oauth2(employee).get("/api/reservations/-1").then().statusCode(400);
        request = registration("valid@example.com");
        request.put("dateOfBirth", "2999-01-01");
        given().auth().oauth2(employee).contentType(ContentType.JSON).body(request)
                .post("/api/customers").then().statusCode(400).body("errors.dateOfBirth", not(emptyOrNullString()));
        given().auth().oauth2(employee).contentType("text/plain").body("invalid")
                .post("/api/brands").then().statusCode(415).body("code", is("UNSUPPORTED_MEDIA_TYPE"));
        given().get("/api/auth/register").then().statusCode(405).body("code", is("METHOD_NOT_ALLOWED"));
    }

    @Test
    void adminCanCreateStaffAndChangeRolesRevokingOldTokens() {
        String admin = login("admin@example.com");
        String oldToken = login("customer@example.com");
        String accountLocation = given().auth().oauth2(admin).contentType(ContentType.JSON)
                .body(Map.of("email", "newemployee@example.com", "password", PASSWORD, "role", "EMPLOYEE"))
                .post("/api/users").then().statusCode(201).body("role", is("EMPLOYEE"))
                .body("passwordHash", nullValue()).extract().header("Location");
        given().auth().oauth2(admin).get(accountLocation).then().statusCode(200)
                .body("email", is("newemployee@example.com"));
        given().auth().oauth2(admin).contentType(ContentType.JSON)
                .body(Map.of("role", "EMPLOYEE", "enabled", true))
                .put("/api/users/" + customerUserId + "/access").then().statusCode(200);
        given().auth().oauth2(oldToken).get("/api/auth/me").then().statusCode(401);
        given().auth().oauth2(login("customer@example.com")).get("/api/customers").then().statusCode(200);
        given().auth().oauth2(admin).get("/api/users").then().statusCode(200)
                .body("passwordHash.findAll { it != null }.size()", is(0));
    }

    @Test
    void disabledUserCannotLoginOrReuseToken() {
        String token = login("employee@example.com");
        given().auth().oauth2(login("admin@example.com")).contentType(ContentType.JSON)
                .body(Map.of("role", "EMPLOYEE", "enabled", false))
                .put("/api/users/" + employeeId + "/access").then().statusCode(200);
        given().auth().oauth2(token).get("/api/auth/me").then().statusCode(401);
        given().contentType(ContentType.JSON).body(Map.of("email", "employee@example.com", "password", PASSWORD))
                .post("/api/auth/login").then().statusCode(401);
    }

    @Test
    void adminCannotRemoveOwnAccessOrCreateCustomerWithoutProfile() {
        String token = login("admin@example.com");
        given().auth().oauth2(token).contentType(ContentType.JSON)
                .body(Map.of("role", "CUSTOMER", "enabled", false))
                .put("/api/users/" + adminId + "/access").then().statusCode(400);
        given().auth().oauth2(token).contentType(ContentType.JSON)
                .body(Map.of("email", "new@example.com", "password", PASSWORD, "role", "CUSTOMER"))
                .post("/api/users").then().statusCode(400);
    }

    @Test
    void onlyAdminCanLinkAnExistingCustomerToAnAccount() {
        Long legacyId = QuarkusTransaction.requiringNew().call(() -> customer("legacy@example.com", "LEGACY").id);
        given().contentType(ContentType.JSON).body(registration("legacy@example.com"))
                .post("/api/auth/register").then().statusCode(409);
        Map<String, Object> request = Map.of("email", "legacy@example.com", "password", PASSWORD,
                "role", "CUSTOMER", "customerId", legacyId);
        given().auth().oauth2(login("employee@example.com")).contentType(ContentType.JSON).body(request)
                .post("/api/users").then().statusCode(403);
        given().auth().oauth2(login("admin@example.com")).contentType(ContentType.JSON).body(request)
                .post("/api/users").then().statusCode(201).body("customerId", is(legacyId.intValue()));
        given().auth().oauth2(login("legacy@example.com")).get("/api/customers/me")
                .then().statusCode(200).body("id", is(legacyId.intValue()));
        given().auth().oauth2(login("admin@example.com")).contentType(ContentType.JSON)
                .body(Map.of("email", "duplicate@example.com", "password", PASSWORD,
                        "role", "CUSTOMER", "customerId", legacyId))
                .post("/api/users").then().statusCode(409);
    }

    @Test
    void migrationPreservesExistingCustomersAndEnforcesAccountConstraints() throws Exception {
        try (PostgreSQLContainer database = new PostgreSQLContainer("postgres:17")) {
            database.start();
            try (var connection = DriverManager.getConnection(database.getJdbcUrl(), database.getUsername(),
                    database.getPassword());
                    var statement = connection.createStatement()) {
                statement.execute("CREATE TABLE public.customers (id BIGINT PRIMARY KEY, email VARCHAR(255))");
                statement.execute("INSERT INTO public.customers VALUES (1, 'existing@example.com')");
                statement.execute(
                        "CREATE TABLE public.reservations (id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, "
                                + "car_id BIGINT NOT NULL, start_date TIMESTAMP NOT NULL, end_date TIMESTAMP NOT NULL, status VARCHAR(30) NOT NULL)");
                Flyway migration = Flyway.configure()
                        .dataSource(database.getJdbcUrl(), database.getUsername(), database.getPassword())
                        .baselineOnMigrate(true).baselineVersion("0").locations("classpath:db/migration").load();
                assertEquals(2, migration.migrate().migrationsExecuted);
                assertEquals(0, migration.migrate().migrationsExecuted);
                try (var rows = statement.executeQuery("SELECT email FROM public.customers WHERE id = 1")) {
                    assertTrue(rows.next());
                    assertEquals("existing@example.com", rows.getString(1));
                }
                statement.execute("INSERT INTO public.app_users (email, password_hash, role, customer_id) "
                        + "VALUES ('existing@example.com', 'test-hash', 'CUSTOMER', 1)");
                assertThrows(SQLException.class,
                        () -> statement.execute("INSERT INTO public.app_users (email, password_hash, role) "
                                + "VALUES ('missing@example.com', 'test-hash', 'CUSTOMER')"));
                assertThrows(SQLException.class,
                        () -> statement.execute("INSERT INTO public.app_users (email, password_hash, role) "
                                + "VALUES ('invalid@example.com', 'test-hash', 'SUPERUSER')"));
                assertThrows(SQLException.class,
                        () -> statement
                                .execute("INSERT INTO public.app_users (email, password_hash, role, customer_id) "
                                        + "VALUES ('duplicate@example.com', 'test-hash', 'CUSTOMER', 1)"));
                statement.execute("INSERT INTO public.reservations (car_id, start_date, end_date, status) "
                        + "VALUES (1, '2027-01-01 10:00', '2027-01-03 10:00', 'PENDING')");
                SQLException conflict = assertThrows(SQLException.class, () -> statement.execute(
                        "INSERT INTO public.reservations (car_id, start_date, end_date, status) "
                                + "VALUES (1, '2027-01-02 10:00', '2027-01-04 10:00', 'CONFIRMED')"));
                assertEquals("23P01", conflict.getSQLState());
                statement.execute("INSERT INTO public.reservations (car_id, start_date, end_date, status) VALUES "
                        + "(1, '2027-01-03 10:00', '2027-01-04 10:00', 'CONFIRMED'), "
                        + "(2, '2027-01-01 10:00', '2027-01-03 10:00', 'PENDING'), "
                        + "(1, '2027-01-01 10:00', '2027-01-03 10:00', 'CANCELLED'), "
                        + "(1, '2027-01-01 10:00', '2027-01-03 10:00', 'COMPLETED')");
            }
        }
    }

    @Test
    void migrationRejectsExistingOverlapsWithoutDeletingBookings() throws Exception {
        try (PostgreSQLContainer database = new PostgreSQLContainer("postgres:17")) {
            database.start();
            try (var connection = DriverManager.getConnection(database.getJdbcUrl(), database.getUsername(),
                    database.getPassword());
                    var statement = connection.createStatement()) {
                statement.execute("CREATE TABLE public.customers (id BIGINT PRIMARY KEY)");
                statement.execute("CREATE TABLE public.reservations (id BIGINT PRIMARY KEY, "
                        + "car_id BIGINT NOT NULL, start_date TIMESTAMP NOT NULL, end_date TIMESTAMP NOT NULL, status VARCHAR(30) NOT NULL)");
                statement.execute("INSERT INTO public.reservations VALUES "
                        + "(1, 1, '2027-01-01 10:00', '2027-01-03 10:00', 'PENDING'), "
                        + "(2, 1, '2027-01-02 10:00', '2027-01-04 10:00', 'CONFIRMED')");
                Flyway migration = Flyway.configure()
                        .dataSource(database.getJdbcUrl(), database.getUsername(), database.getPassword())
                        .baselineOnMigrate(true).baselineVersion("0").locations("classpath:db/migration").load();
                assertThrows(FlywayException.class, migration::migrate);
                try (var rows = statement.executeQuery("SELECT count(*) FROM public.reservations")) {
                    assertTrue(rows.next());
                    assertEquals(2, rows.getLong(1));
                }
            }
        }
    }

    @Test
    void invalidExpiredAndForgedRoleTokensAreRejected() {
        long now = Instant.now().getEpochSecond();
        String forgedRole = Jwt.issuer("car-rental-api").subject(customerUserId.toString())
                .audience("car-rental-api").groups(Set.of("ADMIN")).claim("tokenVersion", 0)
                .expiresAt(now + 60).sign();
        String expired = Jwt.issuer("car-rental-api").subject(customerUserId.toString())
                .audience("car-rental-api").groups(Set.of("CUSTOMER")).claim("tokenVersion", 0)
                .issuedAt(now - 300).expiresAt(now - 120).sign();
        String wrongIssuer = Jwt.issuer("other-issuer").subject(customerUserId.toString())
                .audience("car-rental-api").groups(Set.of("CUSTOMER")).claim("tokenVersion", 0)
                .expiresAt(now + 60).sign();
        String wrongAudience = Jwt.issuer("car-rental-api").subject(customerUserId.toString())
                .audience("other-app").groups(Set.of("CUSTOMER")).claim("tokenVersion", 0)
                .expiresAt(now + 60).sign();
        for (String token : new String[] { "not-a-token", forgedRole, expired, wrongIssuer, wrongAudience }) {
            given().auth().oauth2(token).get("/api/auth/me").then().statusCode(401);
        }
    }

    @Test
    void freshDatabaseCanRunAllMigrationsWithoutAnExternalDump() throws Exception {
        try (PostgreSQLContainer database = new PostgreSQLContainer("postgres:17")) {
            database.start();
            Flyway migrations = Flyway.configure()
                    .dataSource(database.getJdbcUrl(), database.getUsername(), database.getPassword())
                    .locations("classpath:db/migration").load();
            assertEquals(3, migrations.migrate().migrationsExecuted);
            assertEquals(0, migrations.migrate().migrationsExecuted);
            try (var connection = DriverManager.getConnection(database.getJdbcUrl(), database.getUsername(),
                    database.getPassword());
                    var statement = connection.createStatement();
                    var rows = statement.executeQuery(
                            "SELECT count(*) FROM information_schema.tables WHERE table_schema = 'public' AND table_type = 'BASE TABLE'")) {
                assertTrue(rows.next());
                assertEquals(9, rows.getLong(1));
            }
        }
    }

    private String insertDirectBooking(CountDownLatch ready, CountDownLatch start) throws Exception {
        try (var connection = dataSource.getConnection();
                var statement = connection.prepareStatement(
                        "INSERT INTO public.reservations (customer_id, car_id, pickup_location_id, return_location_id, "
                                + "start_date, end_date, total_price, status, created_at) "
                                + "VALUES (?, ?, ?, ?, '2027-01-01 10:00', '2027-01-03 10:00', 20000, 'PENDING', CURRENT_TIMESTAMP)")) {
            connection.setAutoCommit(false);
            statement.setLong(1, customerId);
            statement.setLong(2, carId);
            statement.setLong(3, locationId);
            statement.setLong(4, locationId);
            ready.countDown();
            assertTrue(start.await(10, TimeUnit.SECONDS));
            try {
                statement.executeUpdate();
                connection.commit();
                return "created";
            } catch (SQLException exception) {
                connection.rollback();
                return exception.getSQLState();
            }
        }
    }

    private Long storedReservation(String status, LocalDateTime start, LocalDateTime end) {
        return QuarkusTransaction.requiringNew().call(() -> {
            Reservation reservation = new Reservation();
            reservation.customer = entityManager.find(Customer.class, customerId);
            reservation.car = entityManager.find(Car.class, carId);
            reservation.pickupLocation = entityManager.find(Location.class, locationId);
            reservation.returnLocation = reservation.pickupLocation;
            reservation.startDate = start;
            reservation.endDate = end;
            reservation.totalPrice = new BigDecimal("20000.00");
            reservation.status = status;
            entityManager.persist(reservation);
            return reservation.id;
        });
    }

    private Map<String, Object> carPayload() {
        return new HashMap<>(Map.ofEntries(
                Map.entry("brandId", brandId), Map.entry("currentLocationId", locationId),
                Map.entry("model", "New model"), Map.entry("productionYear", 2024),
                Map.entry("licensePlate", "NEW-PLATE"), Map.entry("vin", "NEW-VIN"),
                Map.entry("fuelType", "PETROL"), Map.entry("transmission", "MANUAL"),
                Map.entry("seatCount", 5), Map.entry("mileage", 0),
                Map.entry("dailyPrice", new BigDecimal("15000.25")), Map.entry("status", "AVAILABLE")));
    }

    private Long anotherCar() {
        return QuarkusTransaction.requiringNew().call(() -> {
            Car original = entityManager.find(Car.class, carId);
            Car another = new Car();
            another.brand = original.brand;
            another.currentLocation = original.currentLocation;
            another.model = original.model;
            another.productionYear = original.productionYear;
            another.licensePlate = "ANOTHER-PLATE";
            another.vin = "ANOTHER-VIN";
            another.fuelType = original.fuelType;
            another.transmission = original.transmission;
            another.seatCount = original.seatCount;
            another.dailyPrice = original.dailyPrice;
            entityManager.persist(another);
            return another.id;
        });
    }

    private Customer customer(String email, String license) {
        Customer customer = new Customer();
        customer.firstName = "Test";
        customer.lastName = "Customer";
        customer.email = email;
        customer.drivingLicenseNumber = license;
        entityManager.persist(customer);
        return customer;
    }

    private User user(String email, Role role, Customer customer) {
        User user = new User();
        user.email = email;
        user.passwordHash = PASSWORD_HASH;
        user.role = role;
        user.customer = customer;
        entityManager.persist(user);
        return user;
    }

    private String login(String email) {
        return given().contentType(ContentType.JSON).body(Map.of("email", email, "password", PASSWORD))
                .post("/api/auth/login").then().statusCode(200).header("Cache-Control", is("no-store"))
                .extract().path("accessToken");
    }

    private Map<String, Object> registration(String email) {
        return new HashMap<>(Map.of("email", email, "password", PASSWORD,
                "firstName", "New", "lastName", "Customer", "drivingLicenseNumber", "NEW-LICENSE"));
    }

    private Map<String, Object> reservation() {
        return new HashMap<>(Map.of("carId", carId, "pickupLocationId", locationId,
                "returnLocationId", locationId, "startDate", "2027-01-01T10:00:00",
                "endDate", "2027-01-03T10:00:00"));
    }
}