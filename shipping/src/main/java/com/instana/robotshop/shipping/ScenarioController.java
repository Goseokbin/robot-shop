package com.instana.robotshop.shipping;

import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.datasource.AbstractDataSource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.sql.DataSource;
import java.lang.reflect.Field;
import java.sql.Connection;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Connection Pool Exhaustion → Alert → Pool Resize 시나리오 컨트롤러.
 *
 * 시나리오 흐름:
 * 1. GET /scenario/pool-status → 정상 상태 확인 (pool=5)
 * 2. GET /scenario/pool-exhaustion → 5개 connection 모두 점유
 * 3. /count 호출 → connection timeout 에러 (Instana 알림)
 * 4. GET /scenario/pool-resize?size=20 → pool 동적 확장
 * 5. /count 호출 → 정상 동작
 * 6. GET /scenario/pool-release → 점유 connection 해제
 */
@RestController
@RequestMapping("/scenario")
public class ScenarioController {
    private static final Logger logger = LoggerFactory.getLogger(ScenarioController.class);

    @Autowired
    private DataSource dataSource;

    private final List<Thread> holdingThreads = Collections.synchronizedList(new ArrayList<>());
    private final List<Long> holdingConnectionThreadIds = Collections.synchronizedList(new ArrayList<>());

    /**
     * RetryableDataSource 래퍼를 벗기고 내부 HikariDataSource를 반환합니다.
     */
    private HikariDataSource unwrapHikariDataSource() {
        DataSource ds = dataSource;
        // RetryableDataSource → delegate 필드에서 HikariDataSource 추출
        if (ds instanceof AbstractDataSource && !(ds instanceof HikariDataSource)) {
            try {
                Field delegateField = ds.getClass().getDeclaredField("delegate");
                delegateField.setAccessible(true);
                ds = (DataSource) delegateField.get(ds);
            } catch (Exception e) {
                throw new RuntimeException("Failed to unwrap HikariDataSource from " + ds.getClass().getName(), e);
            }
        }
        if (ds instanceof HikariDataSource) {
            return (HikariDataSource) ds;
        }
        throw new RuntimeException("DataSource is not HikariDataSource: " + ds.getClass().getName());
    }

    /**
     * Pool의 모든 connection을 SLEEP 쿼리로 점유합니다.
     * Pool이 가득 차면 이후 /count 등의 요청은 connection-timeout 에러가 발생합니다.
     *
     * @param sleepSeconds SLEEP 쿼리 유지 시간 (기본 300초)
     */
    @GetMapping("/pool-exhaustion")
    public Map<String, Object> poolExhaustion(
            @RequestParam(defaultValue = "300") int sleepSeconds) {

        HikariDataSource hikari = unwrapHikariDataSource();
        int poolSize = hikari.getMaximumPoolSize();

        logger.warn("Starting pool-exhaustion scenario: poolSize={}, sleepSeconds={}", poolSize, sleepSeconds);

        // 기존 점유 스레드가 있으면 정리
        releaseHoldingThreads();

        for (int i = 0; i < poolSize; i++) {
            final int idx = i;
            Thread t = new Thread(() -> {
                try {
                    Connection conn = hikari.getConnection();
                    synchronized (holdingConnectionThreadIds) {
                        holdingConnectionThreadIds.add(Thread.currentThread().getId());
                    }
                    logger.info("Pool-exhaustion thread {} acquired connection", idx);
                    try (Statement stmt = conn.createStatement()) {
                        stmt.execute("SELECT SLEEP(" + sleepSeconds + ")");
                    } finally {
                        conn.close();
                        logger.info("Pool-exhaustion thread {} released connection", idx);
                    }
                } catch (Exception e) {
                    logger.warn("Pool-exhaustion thread {} error: {}", idx, e.getMessage());
                }
            }, "pool-exhaust-" + idx);
            t.setDaemon(true);
            holdingThreads.add(t);
            t.start();
        }

        // 잠시 대기하여 connection 획득 완료
        try {
            Thread.sleep(2000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        HikariPoolMXBean poolBean = hikari.getHikariPoolMXBean();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("scenario", "pool-exhaustion");
        result.put("poolSize", poolSize);
        result.put("sleepSeconds", sleepSeconds);
        result.put("holdingThreads", holdingThreads.size());
        if (poolBean != null) {
            result.put("activeConnections", poolBean.getActiveConnections());
            result.put("idleConnections", poolBean.getIdleConnections());
            result.put("threadsAwaitingConnection", poolBean.getThreadsAwaitingConnection());
        }
        result.put("message", "All " + poolSize + " connections are now held. " +
                "Subsequent DB requests (e.g. /count) will timeout after " +
                hikari.getConnectionTimeout() + "ms. Call /scenario/pool-resize?size=20 to expand pool.");
        return result;
    }

    /**
     * HikariCP pool 크기를 동적으로 변경합니다.
     * Pool 고갈 상태에서 호출하면 새 connection이 추가되어 요청이 다시 정상 처리됩니다.
     *
     * @param size 새로운 maximum pool size (기본 20)
     */
    @GetMapping("/pool-resize")
    public Map<String, Object> poolResize(
            @RequestParam(defaultValue = "20") int size) {

        HikariDataSource hikari = unwrapHikariDataSource();
        int oldSize = hikari.getMaximumPoolSize();

        hikari.setMaximumPoolSize(size);
        logger.warn("Pool resized: {} → {}", oldSize, size);

        HikariPoolMXBean poolBean = hikari.getHikariPoolMXBean();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("scenario", "pool-resize");
        result.put("previousSize", oldSize);
        result.put("newSize", size);
        if (poolBean != null) {
            result.put("totalConnections", poolBean.getTotalConnections());
            result.put("activeConnections", poolBean.getActiveConnections());
            result.put("idleConnections", poolBean.getIdleConnections());
            result.put("threadsAwaitingConnection", poolBean.getThreadsAwaitingConnection());
        }
        result.put("message", "Pool resized from " + oldSize + " to " + size +
                ". New connections are now available. Try /count to verify recovery.");
        return result;
    }

    /**
     * 점유 중인 connection을 해제합니다.
     * 점유 스레드를 interrupt하여 SLEEP 쿼리를 중단시킵니다.
     */
    @GetMapping("/pool-release")
    public Map<String, Object> poolRelease() {
        int released = releaseHoldingThreads();

        // pool size도 원래대로 복원
        HikariDataSource hikari = unwrapHikariDataSource();
        int currentSize = hikari.getMaximumPoolSize();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("scenario", "pool-release");
        result.put("releasedThreads", released);
        result.put("currentPoolSize", currentSize);
        result.put("message", "Released " + released + " holding threads. " +
                "Pool size remains at " + currentSize + ". " +
                "Restart the service to reset pool size to default (5).");
        return result;
    }

    /**
     * 현재 pool 상태를 조회합니다.
     */
    @GetMapping("/pool-status")
    public Map<String, Object> poolStatus() {
        HikariDataSource hikari = unwrapHikariDataSource();
        HikariPoolMXBean poolBean = hikari.getHikariPoolMXBean();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("scenario", "pool-status");
        result.put("maximumPoolSize", hikari.getMaximumPoolSize());
        result.put("connectionTimeout", hikari.getConnectionTimeout());
        if (poolBean != null) {
            result.put("totalConnections", poolBean.getTotalConnections());
            result.put("activeConnections", poolBean.getActiveConnections());
            result.put("idleConnections", poolBean.getIdleConnections());
            result.put("threadsAwaitingConnection", poolBean.getThreadsAwaitingConnection());
        }
        result.put("holdingThreads", holdingThreads.size());
        return result;
    }

    /**
     * 점유 스레드를 모두 interrupt하여 connection을 해제합니다.
     */
    private int releaseHoldingThreads() {
        int count;
        synchronized (holdingThreads) {
            count = holdingThreads.size();
            for (Thread t : holdingThreads) {
                t.interrupt();
            }
            holdingThreads.clear();
        }
        synchronized (holdingConnectionThreadIds) {
            holdingConnectionThreadIds.clear();
        }
        if (count > 0) {
            logger.warn("Released {} holding threads", count);
        }
        return count;
    }
}
