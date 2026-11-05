package dev.pisti;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.KeyPairGenerator;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.containers.BindMode;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.startupcheck.OneShotStartupCheckStrategy;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.dockerjava.api.model.Capability;

@EnabledIfSystemProperty(named = "release.image", matches = ".+")
class ProductionImageTest {
    @TempDir
    Path keys;

    @Test
    void packagedImageMigratesAndServesWithReadOnlyFilesystem() throws Exception {
        var generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        var pair = generator.generateKeyPair();
        writeKey("privateKey.pem", "PRIVATE KEY", pair.getPrivate().getEncoded());
        writeKey("publicKey.pem", "PUBLIC KEY", pair.getPublic().getEncoded());
        if (Files.getFileStore(keys).supportsFileAttributeView("posix")) {
            Files.setPosixFilePermissions(keys, PosixFilePermissions.fromString("rwxr-xr-x"));
            Files.setPosixFilePermissions(keys.resolve("privateKey.pem"), PosixFilePermissions.fromString("r--r--r--"));
            Files.setPosixFilePermissions(keys.resolve("publicKey.pem"), PosixFilePermissions.fromString("r--r--r--"));
        }

        try (Network network = Network.newNetwork();
                PostgreSQLContainer database = new PostgreSQLContainer("postgres:17")) {
            database.withDatabaseName("cardealer").withNetwork(network).withNetworkAliases("postgres");
            database.start();
            try (GenericContainer<?> migration = application(network, database)) {
                migration.withCreateContainerCmdModifier(command -> command.withEntrypoint("java"))
                        .withCommand("-Djava.util.logging.manager=org.jboss.logmanager.LogManager",
                                "-jar", "/deployments/quarkus-run.jar", "--migrate")
                        .withStartupCheckStrategy(new OneShotStartupCheckStrategy().withTimeout(Duration.ofMinutes(2)));
                migration.start();
            }
            try (GenericContainer<?> api = application(network, database)) {
                api.withExposedPorts(8080)
                        .waitingFor(Wait.forHttp("/q/health/ready").forPort(8080).forStatusCode(200))
                        .withStartupTimeout(Duration.ofMinutes(2));
                api.start();
                String base = "http://" + api.getHost() + ":" + api.getMappedPort(8080);
                HttpClient client = HttpClient.newHttpClient();
                assertEquals(200, get(client, base + "/api/cars", null).statusCode());
                assertEquals(200, get(client, base + "/q/openapi", null).statusCode());
                assertEquals(200, get(client, base + "/q/swagger-ui/", null).statusCode());
                assertEquals(401, get(client, base + "/api/auth/me", null).statusCode());
                assertEquals(201, post(client, base + "/api/auth/register", """
                        {"email":"release@example.com","password":"release-test-password",
                         "firstName":"Release","lastName":"Test","drivingLicenseNumber":"RELEASE-1"}
                        """).statusCode());
                var login = post(client, base + "/api/auth/login", """
                        {"email":"release@example.com","password":"release-test-password"}
                        """);
                assertEquals(200, login.statusCode());
                String token = new ObjectMapper().readTree(login.body()).get("accessToken").asText();
                assertEquals(200, get(client, base + "/api/auth/me", token).statusCode());
            }
        }
    }

    private GenericContainer<?> application(Network network, PostgreSQLContainer database) {
        GenericContainer<?> container = new GenericContainer<>(
                DockerImageName.parse(System.getProperty("release.image")));
        container.withNetwork(network)
                .withEnv(Map.of(
                        "DB_JDBC_URL", "jdbc:postgresql://postgres:5432/cardealer",
                        "DB_USERNAME", database.getUsername(),
                        "DB_PASSWORD", database.getPassword(),
                        "JWT_PRIVATE_KEY_LOCATION", "file:/run/secrets/jwt/privateKey.pem",
                        "JWT_PUBLIC_KEY_LOCATION", "file:/run/secrets/jwt/publicKey.pem",
                        "QUARKUS_HIBERNATE_ORM_SCHEMA_MANAGEMENT_STRATEGY", "validate"))
                .withFileSystemBind(keys.toAbsolutePath().toString(), "/run/secrets/jwt", BindMode.READ_ONLY)
                .withCreateContainerCmdModifier(command -> command.getHostConfig()
                        .withReadonlyRootfs(true).withTmpFs(Map.of("/tmp", "rw,nosuid,size=64m"))
                        .withCapDrop(Capability.ALL).withSecurityOpts(List.of("no-new-privileges:true")));
        return container;
    }

    private void writeKey(String name, String label, byte[] encoded) throws Exception {
        String content = Base64.getMimeEncoder(64, new byte[] { '\n' }).encodeToString(encoded);
        Files.writeString(keys.resolve(name), "-----BEGIN " + label + "-----\n" + content
                + "\n-----END " + label + "-----\n");
    }

    private HttpResponse<String> get(HttpClient client, String url, String token) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(15));
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        return client.send(request.GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(HttpClient client, String url, String json) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(json)).build(),
                HttpResponse.BodyHandlers.ofString());
    }
}