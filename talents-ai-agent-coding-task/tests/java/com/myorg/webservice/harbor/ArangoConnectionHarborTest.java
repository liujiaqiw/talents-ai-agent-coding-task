package com.myorg.webservice.harbor;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.myorg.webservice.graph.GraphDatabase;
import com.myorg.webservice.graph.GraphDatabaseFactory;
import com.myorg.webservice.graph.GraphStatistics;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Harbor verifier: the connection lifecycle of the ArangoDB family, as far as it can be checked without a server.
 * The race scenarios close the factory before any handshake completes, so they need no server either. ArangoDB
 * cannot run inside the test JVM; tests/test_outputs.py checks that the moved ArangoDB code keeps its queries
 * and driver calls.
 */
@Timeout(value = 60, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class ArangoConnectionHarborTest {

    private static final String[] UNREACHABLE = {"http://127.0.0.1:1", "root", "harbor-secret", "harbor"};

    @Test
    void closeWithoutConnectIsHarmless() throws Exception {
        GraphDatabaseFactory factory = HarborFamilies.newFactory(UNREACHABLE);
        GraphDatabase database = factory.createDatabase();
        GraphStatistics statistics = factory.createStatistics();

        assertThatCode(() -> {
            database.close();
            statistics.close();
            factory.close();
            factory.close();
        }).doesNotThrowAnyException();
    }

    @Test
    void connectFailsWhenTheServerIsUnreachable() throws Exception {
        try (GraphDatabaseFactory factory = HarborFamilies.newFactory(UNREACHABLE)) {
            GraphDatabase database = factory.createDatabase();

            assertThatThrownBy(() -> connect(database)).isInstanceOf(IllegalStateException.class);
            assertThatCode(database::close).as("close after a failed connect").doesNotThrowAnyException();
        }
    }

    @Test
    void closedProductRejectsEveryOperation() throws Exception {
        try (GraphDatabaseFactory factory = HarborFamilies.newFactory(UNREACHABLE)) {
            GraphDatabase database = factory.createDatabase();
            GraphStatistics statistics = factory.createStatistics();
            database.close();
            statistics.close();

            assertThatThrownBy(() -> connect(database)).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> database.findVertex("v")).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> database.upsertVertex("v", Map.of())).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(statistics::vertexCount).isInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    void closedFactoryRejectsNewProducts() throws Exception {
        GraphDatabaseFactory factory = HarborFamilies.newFactory(UNREACHABLE);
        factory.close();

        assertThatThrownBy(factory::createDatabase).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(factory::createStatistics).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void productsOfAClosedFactoryRejectEveryOperation() throws Exception {
        GraphDatabaseFactory factory = HarborFamilies.newFactory(UNREACHABLE);
        GraphDatabase database = factory.createDatabase();
        GraphStatistics statistics = factory.createStatistics();
        factory.close();

        assertThatThrownBy(() -> connect(database)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> database.neighbors("v", 1)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(statistics::edgeCount).isInstanceOf(IllegalStateException.class);
        assertThatCode(database::close).doesNotThrowAnyException();
    }

    @Test
    void closeDoesNotWaitForAConnectInProgress() throws Exception {
        ConnectionRaceScenarios.closeDoesNotWaitForAConnectInProgress(ArangoConnectionHarborTest::argumentsFor);
    }

    @Test
    void closeDuringTheHandshakeLeavesNoConnectionOpen() throws Exception {
        ConnectionRaceScenarios.closeDuringTheHandshakeLeavesNoConnectionOpen(ArangoConnectionHarborTest::argumentsFor,
                null);
    }

    /** Factory arguments for an ArangoDB server at {@code host:port}; there is none, only the test's proxy. */
    private static String[] argumentsFor(String hostAndPort) {
        return new String[] {"http://" + hostAndPort, "root", "harbor-secret", "harbor"};
    }

    /** connect(int, Duration) with a short retry, so that an unreachable server fails fast. */
    private static void connect(GraphDatabase database) throws Throwable {
        Method connect = database.getClass().getMethod("connect", int.class, Duration.class);
        try {
            connect.invoke(database, 2, Duration.ofMillis(100));
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }
}
