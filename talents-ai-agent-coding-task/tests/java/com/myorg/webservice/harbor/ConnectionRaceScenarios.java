package com.myorg.webservice.harbor;

import static org.assertj.core.api.Assertions.assertThat;

import com.myorg.webservice.graph.GraphDatabase;
import com.myorg.webservice.graph.GraphDatabaseFactory;
import com.myorg.webservice.graph.GraphStatistics;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.function.Function;

/**
 * Harbor verifier: the connection lifecycle of a family when several threads use one factory. Each scenario puts a
 * {@link HarborProxy} between the factory and its database, so that the races it checks happen on every run rather
 * than by chance: the proxy holds the driver's handshake while the other threads act, or drops every connection so
 * that a connect keeps retrying.
 *
 * <p>The scenarios take the factory constructor arguments for a {@code host:port}, and the database server to
 * forward to ({@code null} where the family has no server in the verifier).
 */
final class ConnectionRaceScenarios {

    private static final Duration PROMPTLY = Duration.ofSeconds(2);
    private static final Duration DRIVER_TIMEOUT = Duration.ofSeconds(60);

    private ConnectionRaceScenarios() {
    }

    /**
     * Closing the factory while a product's connect() is retrying returns at once, stops the retries and makes
     * that connect() throw IllegalStateException.
     */
    static void closeDoesNotWaitForAConnectInProgress(Function<String, String[]> arguments) throws Exception {
        try (HarborProxy proxy = new HarborProxy(null, HarborProxy.Mode.DROP)) {
            GraphDatabaseFactory factory = HarborFamilies.newFactory(arguments.apply(proxy.address()));
            GraphDatabase database = factory.createDatabase();
            CompletableFuture<Throwable> connecting = inThread(() -> connect(database, 40, Duration.ofMillis(250)));
            proxy.awaitAccepted(1, DRIVER_TIMEOUT);
            Thread.sleep(300);

            assertThat(inThread(factory::close))
                    .as("factory.close() while a connect() of its product retries (10 s of attempts) must not wait "
                            + "for that connect()")
                    .succeedsWithin(PROMPTLY)
                    .isNull();
            assertThat(connecting)
                    .as("a connect() whose factory is closed while it retries must stop retrying")
                    .succeedsWithin(Duration.ofSeconds(5))
                    .as("a connect() whose factory is closed while it retries must throw")
                    .isInstanceOf(IllegalStateException.class);
            assertThat(proxy.awaitAllClosed(Duration.ofSeconds(10))).as("connections left open").isZero();
        }
    }

    /**
     * Closing the factory while a product's connect() is in the server handshake returns at once; when the
     * handshake then completes, that connect() throws IllegalStateException and the driver it opened is closed.
     */
    static void closeDuringTheHandshakeLeavesNoConnectionOpen(Function<String, String[]> arguments,
            InetSocketAddress server) throws Exception {
        try (HarborProxy proxy = new HarborProxy(server, HarborProxy.Mode.HOLD)) {
            GraphDatabaseFactory factory = HarborFamilies.newFactory(arguments.apply(proxy.address()));
            GraphDatabase database = factory.createDatabase();
            CompletableFuture<Throwable> connecting = inThread(() -> connect(database, 3, Duration.ofMillis(250)));
            proxy.awaitAccepted(1, DRIVER_TIMEOUT);
            Thread.sleep(300);

            assertThat(inThread(factory::close))
                    .as("factory.close() while a connect() of its product waits for the server's handshake must not "
                            + "wait for that connect()")
                    .succeedsWithin(PROMPTLY)
                    .isNull();
            proxy.setMode(server == null ? HarborProxy.Mode.DROP : HarborProxy.Mode.FORWARD);
            assertThat(connecting)
                    .as("a connect() whose factory was closed during the handshake must end")
                    .succeedsWithin(DRIVER_TIMEOUT)
                    .as("a connect() whose factory was closed during the handshake must throw, even when the "
                            + "handshake then succeeds")
                    .isInstanceOf(IllegalStateException.class);
            assertThat(proxy.awaitAllClosed(Duration.ofSeconds(10)))
                    .as("connections left open after the factory was closed: the driver opened by a connect() that "
                            + "ran into the close must be closed")
                    .isZero();
        }
    }

    /**
     * Products of one factory that connect at the same time from several threads all end up connected, through
     * one driver: once the factory is closed, no connection to the server is left open.
     */
    static void concurrentConnectsOpenOneConnection(Function<String, String[]> arguments, InetSocketAddress server)
            throws Exception {
        try (HarborProxy proxy = new HarborProxy(server, HarborProxy.Mode.HOLD)) {
            GraphDatabaseFactory factory = HarborFamilies.newFactory(arguments.apply(proxy.address()));
            List<Object> products = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                products.add(i % 2 == 0 ? factory.createDatabase() : factory.createStatistics());
            }
            CountDownLatch start = new CountDownLatch(1);
            List<CompletableFuture<Throwable>> connecting = new ArrayList<>();
            for (Object product : products) {
                connecting.add(inThread(() -> {
                    start.await();
                    connect(product, 5, Duration.ofMillis(200));
                }));
            }
            start.countDown();
            proxy.awaitAccepted(1, DRIVER_TIMEOUT);
            Thread.sleep(500);
            proxy.setMode(HarborProxy.Mode.FORWARD);

            for (int i = 0; i < connecting.size(); i++) {
                assertThat(connecting.get(i))
                        .as("connect() of product %d, called concurrently with the others", i)
                        .succeedsWithin(DRIVER_TIMEOUT)
                        .isNull();
            }
            for (Object product : products) {
                if (product instanceof GraphStatistics statistics) {
                    assertThat(statistics.vertexCount()).isNotNegative();
                } else {
                    assertThat(((GraphDatabase) product).findVertex("harbor-race-nobody")).isEmpty();
                }
            }
            factory.close();
            assertThat(proxy.awaitAllClosed(Duration.ofSeconds(10)))
                    .as("connections left open after the factory was closed: concurrent connect() calls must open "
                            + "one driver, shared by all products, which the factory closes")
                    .isZero();
        }
    }

    /** A step of a scenario that runs in its own thread. */
    @FunctionalInterface
    interface Step {
        void run() throws Throwable;
    }

    /**
     * Runs the step in a new daemon thread, so that a step that never returns cannot hang the run.
     *
     * @return completes with what the step threw, or with {@code null} if it returned normally
     */
    private static CompletableFuture<Throwable> inThread(Step step) {
        CompletableFuture<Throwable> outcome = new CompletableFuture<>();
        Thread thread = new Thread(() -> {
            try {
                step.run();
                outcome.complete(null);
            } catch (Throwable e) {
                outcome.complete(e);
            }
        }, "harbor-race-step");
        thread.setDaemon(true);
        thread.start();
        return outcome;
    }

    /** connect(int, Duration) where the concrete product has it, otherwise connect(). */
    private static void connect(Object product, int attempts, Duration interval) throws Throwable {
        try {
            Method connect = product.getClass().getMethod("connect", int.class, Duration.class);
            connect.invoke(product, attempts, interval);
        } catch (NoSuchMethodException e) {
            ((GraphStatistics) product).connect();
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }
}
