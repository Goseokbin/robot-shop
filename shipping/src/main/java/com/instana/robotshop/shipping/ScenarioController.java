package com.instana.robotshop.shipping;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Chaos/Scenario endpoints for monitoring and testing.
 * Used to simulate failure scenarios in OpenShift/Instana monitoring.
 */
@RestController
@RequestMapping("/scenario")
public class ScenarioController {
    private static final Logger logger = LoggerFactory.getLogger(ScenarioController.class);

    private static final String JDBC_URL_TEMPLATE = "jdbc:mysql://%s/cities?useSSL=false&autoReconnect=true";
    private static final String DB_USER = "shipping";
    private static final String DB_PASSWORD = "secret";

    /**
     * MySQL connection exhaustion scenario.
     * Creates multiple raw JDBC connections and holds them with SLEEP to exhaust MySQL max_connections.
     * This will cause "Too many connections" errors for other services (shipping, ratings).
     *
     * Usage: GET /scenario/mysql-exhaustion?connections=200&hold_seconds=60
     *
     * @param connections Number of connections to create (default: 200, exceeds MySQL default 151)
     * @param holdSeconds How long to hold each connection in seconds (default: 120)
     * @return JSON response with connection count and any errors
     */
    @GetMapping(path = "/mysql-exhaustion")
    public ScenarioResponse mysqlExhaustion(
            @RequestParam(defaultValue = "200") int connections,
            @RequestParam(defaultValue = "120") int holdSeconds) {

        String dbHost = System.getenv("DB_HOST");
        if (dbHost == null) {
            dbHost = "mysql";
        }
        String jdbcUrl = String.format(JDBC_URL_TEMPLATE, dbHost);

        logger.warn("Starting MySQL exhaustion scenario: connections={}, holdSeconds={}", connections, holdSeconds);

        List<String> errors = Collections.synchronizedList(new ArrayList<>());
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failCount = new AtomicInteger(0);
        CountDownLatch startLatch = new CountDownLatch(1);

        Thread[] threads = new Thread[connections];
        for (int i = 0; i < connections; i++) {
            final int idx = i;
            threads[i] = new Thread(() -> {
                Connection conn = null;
                try {
                    startLatch.await();
                    conn = DriverManager.getConnection(jdbcUrl, DB_USER, DB_PASSWORD);
                    successCount.incrementAndGet();

                    try (PreparedStatement ps = conn.prepareStatement("SELECT SLEEP(?)")) {
                        ps.setInt(1, holdSeconds);
                        try (ResultSet rs = ps.executeQuery()) {
                            while (rs.next()) {
                                // blocks until SLEEP completes
                            }
                        }
                    }
                } catch (Exception e) {
                    failCount.incrementAndGet();
                    String errMsg = "Connection " + idx + ": " + e.getMessage();
                    synchronized (errors) {
                        if (errors.size() < 10) {
                            errors.add(errMsg);
                        }
                    }
                    logger.warn("Connection {} failed: {}", idx, e.getMessage());
                } finally {
                    if (conn != null) {
                        try {
                            conn.close();
                        } catch (Exception ignored) {
                        }
                    }
                }
            });
            threads[i].start();
        }

        startLatch.countDown();

        try {
            Thread.sleep(3000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        return new ScenarioResponse(
                "mysql-exhaustion",
                successCount.get(),
                failCount.get(),
                errors,
                "MySQL connections held in background for " + holdSeconds + " seconds. " +
                        "Total established: " + successCount.get() + ", failed: " + failCount.get() + ". " +
                        "Other services may experience 'Too many connections' errors."
        );
    }

    public static class ScenarioResponse {
        public final String scenario;
        public final int connectionsEstablished;
        public final int connectionsFailed;
        public final List<String> errors;
        public final String message;

        public ScenarioResponse(String scenario, int connectionsEstablished, int connectionsFailed,
                               List<String> errors, String message) {
            this.scenario = scenario;
            this.connectionsEstablished = connectionsEstablished;
            this.connectionsFailed = connectionsFailed;
            this.errors = errors;
            this.message = message;
        }
    }
}
