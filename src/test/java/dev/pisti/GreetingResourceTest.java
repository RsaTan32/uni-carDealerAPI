package dev.pisti;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import dev.pisti.entity.Brand;
import dev.pisti.entity.Car;
import dev.pisti.entity.Customer;
import dev.pisti.entity.Location;
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
class GreetingResourceTest {
    private static final String PASSWORD = "test-password-123";
    private static final String PASSWORD_HASH = BcryptUtil.bcryptHash(PASSWORD);

    @Inject
    EntityManager entityManager;

    private Long adminId;
    private Long employeeId;
    private Long customerUserId;
    private Long customerId;
    private Long otherCustomerId;
    private Long carId;
    private Long locationId;

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
        request.put("status", "COMPLETED");
        given().auth().oauth2(token).contentType(ContentType.JSON).body(request)
                .post("/api/reservations").then().statusCode(400);
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
    void employeeCanManageRecordsButCannotManageAccounts() {
        String token = login("employee@example.com");
        given().auth().oauth2(token).contentType(ContentType.JSON).body(Map.of("name", "Employee brand"))
                .post("/api/brands").then().statusCode(201);
        given().auth().oauth2(token).get("/api/customers").then().statusCode(200).body("size()", is(2));
        given().auth().oauth2(token).get("/api/users").then().statusCode(403);
        Map<String, Object> request = reservation();
        request.put("customerId", otherCustomerId);
        request.put("status", "CONFIRMED");
        given().auth().oauth2(token).contentType(ContentType.JSON).body(request)
                .post("/api/reservations").then().statusCode(400);
        request.put("status", "PENDING");
        given().auth().oauth2(token).contentType(ContentType.JSON).body(request)
                .post("/api/reservations").then().statusCode(201)
                .body("customerId", is(otherCustomerId.intValue())).body("status", is("PENDING"));
    }

    @Test
    void adminCanCreateStaffAndChangeRolesRevokingOldTokens() {
        String admin = login("admin@example.com");
        String oldToken = login("customer@example.com");
        given().auth().oauth2(admin).contentType(ContentType.JSON)
                .body(Map.of("email", "newemployee@example.com", "password", PASSWORD, "role", "EMPLOYEE"))
                .post("/api/users").then().statusCode(201).body("role", is("EMPLOYEE"))
                .body("passwordHash", nullValue());
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