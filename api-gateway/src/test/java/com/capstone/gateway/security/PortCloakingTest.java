package com.capstone.gateway.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketException;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;

/**
 * NFR-SEC-01: Zero-Trust Port Cloaking (CH-03)
 * Jira: DIG-8, DIG-25, DIG-118
 */
@Disabled("NFR CH-03 Live Environment Test: Run via scripts/chaos/scenario3_perimeter_scan.ps1 when Docker stack is active.")
@ExtendWith(MockitoExtension.class)
class PortCloakingTest {

    private static final String LOCALHOST = "127.0.0.1";
    private static final int SOCKET_TIMEOUT_MS = 500;
    private static final int CONNECTION_REFUSED_WAIT_MS = 100;

    private List<Integer> externalPorts;
    private List<Integer> internalPorts;

    @BeforeEach
    void setUp() {
        this.externalPorts = Arrays.asList(8080, 8090);
        this.internalPorts = Arrays.asList(8081, 8082, 8083, 8084, 8085, 8086, 8087, 8088, 1521, 5432);
    }

    @Test
    @DisplayName("PORT-SEC-01: External ports accept connections")
    void portSec01_externalPortsAcceptConnections() {
        for (Integer port : externalPorts) {
            boolean canConnect = attemptConnection(LOCALHOST, port, SOCKET_TIMEOUT_MS);
            assertThat(canConnect).as("External port %d should accept connections", port).isTrue();
        }
    }

    @Test
    @DisplayName("PORT-SEC-02: Internal service ports refuse connections")
    void portSec02_internalServicePortsRefused() {
        List<Integer> internalServicePorts = Arrays.asList(8081, 8082, 8083, 8084, 8085, 8086, 8087, 8088);
        for (Integer port : internalServicePorts) {
            boolean canConnect = attemptConnection(LOCALHOST, port, SOCKET_TIMEOUT_MS);
            assertThat(canConnect).as("Internal service port %d should refuse connections", port).isFalse();
        }
    }

    @Test
    @DisplayName("PORT-SEC-03: Database ports refuse connections")
    void portSec03_databasePortsRefused() {
        List<Integer> databasePorts = Arrays.asList(1521, 5432);
        for (Integer port : databasePorts) {
            boolean canConnect = attemptConnection(LOCALHOST, port, SOCKET_TIMEOUT_MS);
            assertThat(canConnect).as("Database port %d should refuse connections", port).isFalse();
        }
    }

    @Test
    @DisplayName("PORT-SEC-04: Connection refusal is fast")
    void portSec04_connectionRefusalIsFast() {
        for (Integer port : internalPorts) {
            long startTime = System.currentTimeMillis();
            attemptConnection(LOCALHOST, port, SOCKET_TIMEOUT_MS);
            long elapsed = System.currentTimeMillis() - startTime;
            assertThat(elapsed).as("Port %d rejection should occur quickly", port).isLessThan(CONNECTION_REFUSED_WAIT_MS);
        }
    }

    @Test
    @DisplayName("PORT-SEC-05: Concurrent port scans all blocked")
    void portSec05_concurrentPortScansAllBlocked() throws InterruptedException {
        int threadCount = 10;
        AtomicInteger successfulConnections = new AtomicInteger(0);
        Thread[] threads = new Thread[threadCount];
        for (int i = 0; i < threadCount; i++) {
            final int threadId = i;
            threads[i] = new Thread(() -> {
                Integer port = internalPorts.get(threadId % internalPorts.size());
                if (attemptConnection(LOCALHOST, port, SOCKET_TIMEOUT_MS)) {
                    successfulConnections.incrementAndGet();
                }
            });
            threads[i].start();
        }
        for (Thread t : threads) {
            t.join(2000);
        }
        assertThat(successfulConnections.get()).isEqualTo(0);
    }

    @Test
    @DisplayName("PORT-SEC-06: Sequential scan detects only exposed ports")
    void portSec06_sequentialScanDetectsOnlyExposed() {
        List<Integer> allPorts = Arrays.asList(1521, 5432, 8080, 8081, 8082, 8083, 8084, 8085, 8086, 8087, 8088, 8090);
        int exposedCount = 0;
        for (Integer port : allPorts) {
            if (attemptConnection(LOCALHOST, port, SOCKET_TIMEOUT_MS)) {
                exposedCount++;
            }
        }
        assertThat(exposedCount).isEqualTo(externalPorts.size());
    }

    @Test
    @DisplayName("PORT-SEC-07: Grafana port cloaked")
    void portSec07_grafanaPortCloaked() {
        boolean canConnect = attemptConnection(LOCALHOST, 3000, SOCKET_TIMEOUT_MS);
        assertThat(canConnect).isFalse();
    }

    @Test
    @DisplayName("PORT-SEC-08: Kafka port cloaked")
    void portSec08_kafkaPortCloaked() {
        boolean canConnect = attemptConnection(LOCALHOST, 9092, SOCKET_TIMEOUT_MS);
        assertThat(canConnect).isFalse();
    }

    @Test
    @DisplayName("PORT-SEC-09: Zookeeper port cloaked")
    void portSec09_zookeeperPortCloaked() {
        boolean canConnect = attemptConnection(LOCALHOST, 2181, SOCKET_TIMEOUT_MS);
        assertThat(canConnect).isFalse();
    }

    @Test
    @DisplayName("PORT-SEC-10: Redis port cloaked")
    void portSec10_redisPortCloaked() {
        boolean canConnect = attemptConnection(LOCALHOST, 6379, SOCKET_TIMEOUT_MS);
        assertThat(canConnect).isFalse();
    }

    @Test
    @DisplayName("PORT-SEC-11: Prometheus port cloaked")
    void portSec11_prometheusPortCloaked() {
        boolean canConnect = attemptConnection(LOCALHOST, 9090, SOCKET_TIMEOUT_MS);
        assertThat(canConnect).isFalse();
    }

    @Test
    @DisplayName("PORT-SEC-12: Loki port cloaked")
    void portSec12_lokiPortCloaked() {
        boolean canConnect = attemptConnection(LOCALHOST, 3100, SOCKET_TIMEOUT_MS);
        assertThat(canConnect).isFalse();
    }

    @Test
    @DisplayName("PORT-SEC-13: Admin API port exposed")
    void portSec13_adminPortalExposed() {
        boolean canConnect = attemptConnection(LOCALHOST, 8090, SOCKET_TIMEOUT_MS);
        assertThat(canConnect).isTrue();
    }

    @Test
    @DisplayName("PORT-SEC-14: SSH port not listening")
    void portSec14_sshPortNotListening() {
        boolean canConnect = attemptConnection(LOCALHOST, 22, SOCKET_TIMEOUT_MS);
        assertThat(canConnect).isFalse();
    }

    private boolean attemptConnection(String host, int port, int timeoutMs) {
        Socket socket = null;
        try {
            socket = new Socket();
            socket.setSoTimeout(timeoutMs);
            socket.connect(new InetSocketAddress(host, port), timeoutMs);
            socket.close();
            return true;
        } catch (SocketException e) {
            return false;
        } catch (IOException e) {
            return false;
        } finally {
            if (socket != null && !socket.isClosed()) {
                try {
                    socket.close();
                } catch (IOException e) {
                    // Ignore
                }
            }
        }
    }
}
