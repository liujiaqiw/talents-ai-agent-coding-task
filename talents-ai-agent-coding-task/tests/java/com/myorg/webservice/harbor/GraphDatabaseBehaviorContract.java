package com.myorg.webservice.harbor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.myorg.webservice.graph.GraphDatabase;
import com.myorg.webservice.graph.GraphDatabaseFactory;
import com.myorg.webservice.graph.GraphStatistics;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Harbor verifier: the behaviour and connection lifecycle every family must provide, run against a real database of
 * the family under test, which the subclass runs inside the test JVM. Each subclass names one family by the
 * arguments of its factory; the database is only reached through the factory and the abstract products, except for
 * {@code connect(int, Duration)}, the public retry variant of every concrete {@link GraphDatabase}.
 *
 * <p>Every test writes vertices with its own random ID prefix, so tests and repeated runs never see each other's
 * data on the shared servers; statistics are compared before and after a test's own writes. Timeouts run each test
 * in a separate thread, so that a database call that ignores interrupts still fails its test instead of hanging the
 * run.
 */
@Timeout(value = 120, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
abstract class GraphDatabaseBehaviorContract {

    static final String PASSWORD = "harbor-secret";

    private final String prefix = "h" + UUID.randomUUID().toString().replace("-", "").substring(0, 12) + "-";
    private final List<GraphDatabaseFactory> factories = new ArrayList<>();

    /** The factory constructor arguments for the subclass's database server. */
    abstract String[] serverArguments();

    /** The factory constructor arguments for a server address where nothing listens. */
    abstract String[] unreachableArguments();

    /** The factory constructor arguments for a server of the subclass's database at {@code host:port}. */
    abstract String[] argumentsFor(String hostAndPort);

    /** The address of the subclass's database server. */
    abstract InetSocketAddress serverAddress();

    @AfterEach
    void closeFactories() {
        factories.forEach(GraphDatabaseFactory::close);
    }

    // ------------------------------------------------------------------ schema and vertices

    @Test
    void ensureSchemaIsIdempotent() throws Exception {
        GraphDatabase database = connected();
        assertThatCode(database::ensureSchema).doesNotThrowAnyException();
        assertThatCode(() -> connected().ensureSchema()).doesNotThrowAnyException();
    }

    @Test
    void findVertexReturnsTheStoredProperties() throws Exception {
        GraphDatabase database = connected();
        database.upsertVertex(id("alice"), Map.of("name", "Alice", "city", "Paris", "active", true));

        assertThat(database.findVertex(id("alice")))
                .as("only the properties given to upsertVertex, without the ID or internal fields")
                .contains(Map.of("name", "Alice", "city", "Paris", "active", true));
    }

    @Test
    void findVertexOfUnknownIdIsEmpty() throws Exception {
        assertThat(connected().findVertex(id("nobody"))).isEmpty();
    }

    @Test
    void upsertVertexMergesPropertiesIntoTheExistingVertex() throws Exception {
        GraphDatabase database = connected();
        database.upsertVertex(id("bob"), Map.of("name", "Bob", "city", "Oslo"));
        database.upsertVertex(id("bob"), Map.of("city", "Rome", "team", "blue"));

        assertThat(database.findVertex(id("bob"))).contains(Map.of("name", "Bob", "city", "Rome", "team", "blue"));
    }

    @Test
    void writesAreStoredOnTheServer() throws Exception {
        connected().upsertVertex(id("carol"), Map.of("name", "Carol"));

        assertThat(connected().findVertex(id("carol")))
                .as("a product of a second factory, with its own connection, reads what the first one wrote")
                .contains(Map.of("name", "Carol"));
    }

    // ------------------------------------------------------------------ edges and traversal

    @Test
    void neighborsAreOrderedByHopsAndThenById() throws Exception {
        GraphDatabase database = sampleGraph(connected());

        assertThat(database.neighbors(id("s"), 10))
                .as("hop 1: k, m; hop 2: c; hop 3: z; hop 4: b; never the start vertex s or x, which only links to s")
                .containsExactly(id("k"), id("m"), id("c"), id("z"), id("b"));
    }

    @Test
    void neighborsStopAtTheGivenDepth() throws Exception {
        GraphDatabase database = sampleGraph(connected());

        assertThat(database.neighbors(id("s"), 1)).containsExactly(id("k"), id("m"));
        assertThat(database.neighbors(id("s"), 2)).containsExactly(id("k"), id("m"), id("c"));
        assertThat(database.neighbors(id("s"), 3)).containsExactly(id("k"), id("m"), id("c"), id("z"));
    }

    @Test
    void neighborsFollowOutboundEdgesOnly() throws Exception {
        GraphDatabase database = sampleGraph(connected());

        assertThat(database.neighbors(id("b"), 5)).as("b has only an inbound edge").isEmpty();
        assertThat(database.neighbors(id("x"), 1)).containsExactly(id("s"));
    }

    @Test
    void upsertEdgeTwiceKeepsOneEdge() throws Exception {
        GraphDatabaseFactory factory = factory();
        GraphDatabase database = factory.createDatabase();
        database.connect();
        database.ensureSchema();
        GraphStatistics statistics = factory.createStatistics();
        database.upsertVertex(id("from"), Map.of());
        database.upsertVertex(id("to"), Map.of());
        database.upsertEdge(id("from"), id("to"), Map.of("note", "first"));
        database.upsertEdge(id("from"), id("to"), Map.of("note", "second"));

        assertThat(database.neighbors(id("from"), 3)).containsExactly(id("to"));
        assertThat(statistics.outDegree(id("from"))).as("the second upsertEdge merges into the first edge")
                .isEqualTo(1);
    }

    @Test
    void neighborsOfUnknownVertexAreEmpty() throws Exception {
        assertThat(connected().neighbors(id("nobody"), 3)).isEmpty();
    }

    @Test
    void neighborsWithDepthBelowOneAreEmpty() throws Exception {
        GraphDatabase database = sampleGraph(connected());

        assertThat(database.neighbors(id("s"), 0)).isEmpty();
        assertThat(database.neighbors(id("s"), -1)).isEmpty();
    }

    // ------------------------------------------------------------------ statistics

    @Test
    void statisticsCountWhatTheDatabaseStores() throws Exception {
        GraphDatabaseFactory factory = factory();
        GraphDatabase database = factory.createDatabase();
        database.connect();
        database.ensureSchema();
        GraphStatistics statistics = factory.createStatistics();
        long vertices = statistics.vertexCount();
        long edges = statistics.edgeCount();

        sampleGraph(database);
        assertThat(statistics.vertexCount()).as("the sample graph adds 7 vertices").isEqualTo(vertices + 7);
        assertThat(statistics.edgeCount()).as("the sample graph adds 8 edges").isEqualTo(edges + 8);

        sampleGraph(database);
        assertThat(statistics.vertexCount()).as("upserting the same vertices again adds none").isEqualTo(vertices + 7);
        assertThat(statistics.edgeCount()).as("upserting the same edges again adds none").isEqualTo(edges + 8);
    }

    @Test
    void outDegreeCountsOutboundEdges() throws Exception {
        GraphDatabaseFactory factory = factory();
        GraphDatabase database = factory.createDatabase();
        database.connect();
        database.ensureSchema();
        sampleGraph(database);
        GraphStatistics statistics = factory.createStatistics();

        assertThat(statistics.outDegree(id("s"))).isEqualTo(2);
        assertThat(statistics.outDegree(id("c"))).isEqualTo(2);
        assertThat(statistics.outDegree(id("b"))).as("b has only an inbound edge").isZero();
        assertThat(statistics.outDegree(id("nobody"))).isZero();
    }

    // ------------------------------------------------------------------ connection lifecycle

    @Test
    void statisticsUseTheConnectionOpenedByTheDatabase() throws Exception {
        GraphDatabaseFactory factory = factory();
        GraphDatabase database = factory.createDatabase();
        database.connect();
        database.ensureSchema();
        database.upsertVertex(id("solo"), Map.of());
        GraphStatistics statistics = factory.createStatistics();

        assertThat(statistics.outDegree(id("solo")))
                .as("statistics of the same factory work without their own connect()")
                .isZero();
    }

    @Test
    void productsOfOneFactoryShareOneDriver() throws Exception {
        GraphDatabaseFactory factory = factory();
        GraphDatabase database = factory.createDatabase();
        GraphStatistics statistics = factory.createStatistics();
        GraphDatabase another = factory.createDatabase();
        database.connect();
        statistics.connect();
        another.connect();

        assertThat(HarborFamilies.drivers(factory, database, statistics, another))
                .as("the factory and all its products hold exactly one driver connection")
                .hasSize(1);
    }

    @Test
    void closingAProductKeepsTheOtherProductsWorking() throws Exception {
        GraphDatabaseFactory factory = factory();
        GraphDatabase first = factory.createDatabase();
        GraphDatabase second = factory.createDatabase();
        GraphStatistics statistics = factory.createStatistics();
        first.connect();
        first.ensureSchema();
        first.upsertVertex(id("kept"), Map.of("name", "kept"));

        first.close();
        assertThat(second.findVertex(id("kept"))).contains(Map.of("name", "kept"));
        assertThat(statistics.outDegree(id("kept"))).isZero();
    }

    @Test
    void closedProductRejectsEveryOperation() throws Exception {
        GraphDatabaseFactory factory = factory();
        GraphDatabase database = factory.createDatabase();
        GraphStatistics statistics = factory.createStatistics();
        database.connect();
        database.ensureSchema();
        database.close();
        statistics.close();

        assertThatThrownBy(database::connect).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(database::ensureSchema).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> database.upsertVertex(id("late"), Map.of())).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> database.upsertEdge(id("a"), id("b"), Map.of()))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> database.findVertex(id("late"))).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> database.neighbors(id("late"), 1)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(statistics::connect).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(statistics::vertexCount).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(statistics::edgeCount).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> statistics.outDegree(id("late"))).isInstanceOf(IllegalStateException.class);
        assertThatCode(database::close).as("closing a product twice").doesNotThrowAnyException();
    }

    @Test
    void closingTheFactoryClosesItsConnectionForAllProducts() throws Exception {
        GraphDatabaseFactory factory = factory();
        GraphDatabase database = factory.createDatabase();
        GraphStatistics statistics = factory.createStatistics();
        database.connect();
        database.ensureSchema();
        assertThat(statistics.edgeCount()).isNotNegative();

        factory.close();
        assertThatThrownBy(() -> database.findVertex(id("late"))).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(statistics::vertexCount).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(database::connect).as("a closed factory's connection cannot be reopened")
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(factory::createDatabase).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(factory::createStatistics).isInstanceOf(IllegalStateException.class);
        assertThatCode(factory::close).as("closing a factory twice").doesNotThrowAnyException();
    }

    @Test
    void factoriesOfTheSameFamilyAreIndependent() throws Exception {
        GraphDatabase closedOne = connected();
        GraphDatabase openOne = connected();
        openOne.upsertVertex(id("independent"), Map.of("name", "independent"));

        factories.get(0).close();
        assertThatThrownBy(() -> closedOne.findVertex(id("independent"))).isInstanceOf(IllegalStateException.class);
        assertThat(openOne.findVertex(id("independent"))).contains(Map.of("name", "independent"));
    }

    @Test
    void closeWithoutConnectIsHarmless() throws Exception {
        GraphDatabaseFactory factory = HarborFamilies.newFactory(unreachableArguments());
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
    @Timeout(value = 60, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void connectFailsWhenTheServerIsUnreachable() throws Exception {
        GraphDatabaseFactory factory = HarborFamilies.newFactory(unreachableArguments());
        factories.add(factory);
        GraphDatabase database = factory.createDatabase();
        Method connect = database.getClass().getMethod("connect", int.class, Duration.class);

        assertThatThrownBy(() -> invoke(connect, database, 2, Duration.ofMillis(100)))
                .isInstanceOf(IllegalStateException.class);
        assertThatCode(database::close).as("close after a failed connect").doesNotThrowAnyException();
    }

    // ------------------------------------------------------------------ several threads

    @Test
    void closeDoesNotWaitForAConnectInProgress() throws Exception {
        ConnectionRaceScenarios.closeDoesNotWaitForAConnectInProgress(this::argumentsFor);
    }

    @Test
    void closeDuringTheHandshakeLeavesNoConnectionOpen() throws Exception {
        ConnectionRaceScenarios.closeDuringTheHandshakeLeavesNoConnectionOpen(this::argumentsFor, serverAddress());
    }

    @Test
    void concurrentConnectsOpenOneConnection() throws Exception {
        ConnectionRaceScenarios.concurrentConnectsOpenOneConnection(this::argumentsFor, serverAddress());
    }

    // ------------------------------------------------------------------ helpers

    private String id(String name) {
        return prefix + name;
    }

    /** A new factory for the subclass's database server, closed after the test. */
    private GraphDatabaseFactory factory() throws Exception {
        GraphDatabaseFactory factory = HarborFamilies.newFactory(serverArguments());
        factories.add(factory);
        return factory;
    }

    /** A graph database of a new factory, connected to the server and with the schema in place. */
    private GraphDatabase connected() throws Exception {
        GraphDatabase database = factory().createDatabase();
        database.connect();
        database.ensureSchema();
        return database;
    }

    /**
     * s -> m, s -> k, k -> c, m -> c, c -> s, c -> z, z -> b and x -> s, written in an order that is neither the hop
     * order nor the ID order, so that only real sorting gives the expected neighbor lists.
     */
    private GraphDatabase sampleGraph(GraphDatabase database) {
        for (String vertex : List.of("z", "b", "x", "m", "c", "s", "k")) {
            database.upsertVertex(id(vertex), Map.of("name", vertex));
        }
        String[][] edges = {{"c", "z"}, {"s", "m"}, {"x", "s"}, {"m", "c"}, {"z", "b"}, {"s", "k"}, {"c", "s"},
            {"k", "c"}};
        for (String[] edge : edges) {
            database.upsertEdge(id(edge[0]), id(edge[1]), Map.of("weight", "1"));
        }
        return database;
    }

    private static void invoke(Method method, Object target, Object... arguments) throws Throwable {
        try {
            method.invoke(target, arguments);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }
}
