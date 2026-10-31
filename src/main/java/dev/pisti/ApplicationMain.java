package dev.pisti;

import io.quarkus.runtime.Quarkus;
import io.quarkus.runtime.QuarkusApplication;
import io.quarkus.runtime.annotations.QuarkusMain;

@QuarkusMain
public class ApplicationMain {
    public static void main(String... args) {
        if (args.length == 1 && "--migrate".equals(args[0])) {
            System.setProperty("quarkus.flyway.migrate-at-start", "true");
            Quarkus.run(MigrationCommand.class, args);
        } else {
            Quarkus.run(args);
        }
    }

    public static class MigrationCommand implements QuarkusApplication {
        @Override
        public int run(String... args) {
            return 0;
        }
    }
}