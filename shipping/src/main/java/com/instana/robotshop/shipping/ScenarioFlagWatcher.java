package com.instana.robotshop.shipping;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class ScenarioFlagWatcher {
    private static final Logger logger = LoggerFactory.getLogger(ScenarioFlagWatcher.class);

    private static final String FLAG_MEMORY_LEAK = "scenario-memory-leak";
    private static final String FLAG_POOL_EXHAUSTION = "scenario-pool-exhaustion";

    @Value("${flagd.ofrep.url:}")
    private String flagdUrl;

    @Autowired
    private ScenarioController scenarioController;

    private final RestTemplate restTemplate = new RestTemplate();

    private boolean memoryLeakTriggered = false;
    private boolean poolExhaustionTriggered = false;

    @Scheduled(fixedDelay = 5000)
    public void checkFlags() {
        if (flagdUrl == null || flagdUrl.isEmpty()) {
            return;
        }

        checkMemoryLeakFlag();
        checkPoolExhaustionFlag();
    }

    private void checkMemoryLeakFlag() {
        boolean flagValue = evaluateFlag(FLAG_MEMORY_LEAK);

        if (flagValue && !memoryLeakTriggered) {
            logger.warn("[FlagWatcher] Flag '{}' is ON — triggering memory leak scenario", FLAG_MEMORY_LEAK);
            try {
                scenarioController.triggerMemoryLeak(25, 20);
                memoryLeakTriggered = true;
                logger.warn("[FlagWatcher] Memory leak scenario triggered successfully");
            } catch (Exception e) {
                logger.error("[FlagWatcher] Failed to trigger memory leak scenario: {}", e.getMessage());
            }
        } else if (!flagValue && memoryLeakTriggered) {
            logger.info("[FlagWatcher] Flag '{}' is OFF — resetting triggered state", FLAG_MEMORY_LEAK);
            memoryLeakTriggered = false;
        }
    }

    private void checkPoolExhaustionFlag() {
        boolean flagValue = evaluateFlag(FLAG_POOL_EXHAUSTION);

        if (flagValue && !poolExhaustionTriggered) {
            logger.warn("[FlagWatcher] Flag '{}' is ON — triggering pool exhaustion scenario", FLAG_POOL_EXHAUSTION);
            try {
                scenarioController.triggerPoolExhaustion(300);
                poolExhaustionTriggered = true;
                logger.warn("[FlagWatcher] Pool exhaustion scenario triggered successfully");
            } catch (Exception e) {
                logger.error("[FlagWatcher] Failed to trigger pool exhaustion scenario: {}", e.getMessage());
            }
        } else if (!flagValue && poolExhaustionTriggered) {
            logger.info("[FlagWatcher] Flag '{}' is OFF — resetting triggered state", FLAG_POOL_EXHAUSTION);
            poolExhaustionTriggered = false;
        }
    }

    @SuppressWarnings("unchecked")
    private boolean evaluateFlag(String flagKey) {
        try {
            String url = flagdUrl + "/ofrep/v1/evaluate/flags/" + flagKey;

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<String> request = new HttpEntity<>("{}", headers);

            ResponseEntity<Map> response = restTemplate.postForEntity(url, request, Map.class);

            if (response.getBody() != null) {
                Object value = response.getBody().get("value");
                return Boolean.TRUE.equals(value);
            }
        } catch (Exception e) {
            logger.debug("[FlagWatcher] Failed to evaluate flag '{}': {}", flagKey, e.getMessage());
        }
        return false;
    }
}
