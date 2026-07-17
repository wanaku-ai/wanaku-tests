package ai.wanaku.test.router;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import io.quarkus.test.junit.QuarkusTest;
import ai.wanaku.test.model.HttpToolConfig;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;

@QuarkusTest
class ConcurrentOperationsITCase extends RouterTestBase {

    @BeforeEach
    void assumeRouterAvailable() {
        assumeThat(isRouterAvailable()).as("Router must be available").isTrue();
    }

    @DisplayName("Register multiple tools concurrently without errors")
    @Test
    void shouldHandleConcurrentToolRegistration() throws Exception {
        int threadCount = 5;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failureCount = new AtomicInteger(0);

        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < threadCount; i++) {
            final int index = i;
            futures.add(executor.submit(() -> {
                try {
                    startLatch.await();
                    routerClient.registerTool(HttpToolConfig.builder()
                            .name("concurrent-tool-" + index)
                            .description("Tool registered concurrently #" + index)
                            .uri("https://httpbin.org/get?id=" + index)
                            .build());
                    successCount.incrementAndGet();
                } catch (Exception e) {
                    failureCount.incrementAndGet();
                }
            }));
        }

        startLatch.countDown();

        for (Future<?> future : futures) {
            future.get(30, TimeUnit.SECONDS);
        }
        executor.shutdown();

        assertThat(successCount.get()).isEqualTo(threadCount);
        assertThat(failureCount.get()).isZero();
        assertThat(routerClient.listTools()).hasSize(threadCount);
    }

    @DisplayName("Concurrent list operations do not interfere with each other")
    @Test
    void shouldHandleConcurrentListOperations() throws Exception {
        routerClient.registerTool(HttpToolConfig.builder()
                .name("concurrent-list-tool")
                .description("Tool for concurrent list test")
                .uri("https://httpbin.org/get")
                .build());

        int threadCount = 10;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        AtomicInteger successCount = new AtomicInteger(0);

        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < threadCount; i++) {
            futures.add(executor.submit(() -> {
                try {
                    startLatch.await();
                    assertThat(routerClient.listTools()).isNotEmpty();
                    successCount.incrementAndGet();
                } catch (Exception e) {
                    // ignore
                }
            }));
        }

        startLatch.countDown();

        for (Future<?> future : futures) {
            future.get(30, TimeUnit.SECONDS);
        }
        executor.shutdown();

        assertThat(successCount.get()).isEqualTo(threadCount);
    }

    @DisplayName("Concurrent data store uploads do not lose entries")
    @Test
    void shouldHandleConcurrentDataStoreUploads() throws Exception {
        assumeThat(dataStoreClient.isAvailable())
                .as("Data store must be available")
                .isTrue();

        int threadCount = 5;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        AtomicInteger successCount = new AtomicInteger(0);

        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < threadCount; i++) {
            final int index = i;
            futures.add(executor.submit(() -> {
                try {
                    startLatch.await();
                    dataStoreClient.upload("concurrent-entry-" + index, "content-" + index);
                    successCount.incrementAndGet();
                } catch (Exception e) {
                    // ignore
                }
            }));
        }

        startLatch.countDown();

        for (Future<?> future : futures) {
            future.get(30, TimeUnit.SECONDS);
        }
        executor.shutdown();

        assertThat(successCount.get()).isEqualTo(threadCount);
        assertThat(dataStoreClient.list()).hasSize(threadCount);
    }
}
