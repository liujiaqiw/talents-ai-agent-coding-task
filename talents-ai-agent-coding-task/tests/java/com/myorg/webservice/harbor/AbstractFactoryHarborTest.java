package com.myorg.webservice.harbor;

import static org.assertj.core.api.Assertions.assertThat;

import com.myorg.webservice.graph.GraphDatabase;
import com.myorg.webservice.graph.GraphDatabaseFactory;
import com.myorg.webservice.graph.GraphStatistics;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Harbor verifier: the graph package is an Abstract Factory whose families each create a matching
 * {@link GraphDatabase} and {@link GraphStatistics} from their own connection parameters.
 */
class AbstractFactoryHarborTest {

    private static final String[] ARANGO = {"http://arango.harbor.invalid:8529", "harbor-user", "harbor-secret",
        "harbor_db"};
    private static final String[] NEO4J = {"bolt://neo4j.harbor.invalid:7687", "harbor-user", "harbor-secret"};
    private static final String[] JANUSGRAPH = {"ws://janusgraph.harbor.invalid:8182/gremlin"};

    // ------------------------------------------------------------------ abstractions

    @Test
    void abstractFactoryCreatesBothAbstractProducts() throws NoSuchMethodException {
        assertThat(GraphDatabaseFactory.class.isInterface()).as("GraphDatabaseFactory must be an interface").isTrue();
        assertThat(AutoCloseable.class.isAssignableFrom(GraphDatabaseFactory.class)).isTrue();
        assertThat(GraphDatabaseFactory.class.getMethod("createDatabase").getReturnType())
                .isEqualTo(GraphDatabase.class);
        assertThat(GraphDatabaseFactory.class.getMethod("createStatistics").getReturnType())
                .isEqualTo(GraphStatistics.class);
        assertThat(GraphDatabaseFactory.class.getMethods())
                .as("createDatabase(), createStatistics() and close() are the only abstract operations")
                .filteredOn(method -> Modifier.isAbstract(method.getModifiers()))
                .extracting(Method::getName)
                .containsExactlyInAnyOrder("createDatabase", "createStatistics", "close");
    }

    @Test
    void productsKeepTheirOperations() throws NoSuchMethodException {
        assertThat(GraphDatabase.class.isInterface()).isTrue();
        GraphDatabase.class.getMethod("connect");
        GraphDatabase.class.getMethod("ensureSchema");
        GraphDatabase.class.getMethod("upsertVertex", String.class, Map.class);
        GraphDatabase.class.getMethod("upsertEdge", String.class, String.class, Map.class);
        GraphDatabase.class.getMethod("findVertex", String.class);
        GraphDatabase.class.getMethod("neighbors", String.class, int.class);
        GraphDatabase.class.getMethod("close");
        assertThat(AutoCloseable.class.isAssignableFrom(GraphDatabase.class)).isTrue();

        assertThat(GraphStatistics.class.isInterface()).isTrue();
        GraphStatistics.class.getMethod("connect");
        assertThat(GraphStatistics.class.getMethod("vertexCount").getReturnType()).isEqualTo(long.class);
        assertThat(GraphStatistics.class.getMethod("edgeCount").getReturnType()).isEqualTo(long.class);
        assertThat(GraphStatistics.class.getMethod("outDegree", String.class).getReturnType()).isEqualTo(long.class);
        GraphStatistics.class.getMethod("close");
        assertThat(AutoCloseable.class.isAssignableFrom(GraphStatistics.class)).isTrue();
    }

    // ------------------------------------------------------------------ families

    @Test
    @Timeout(20)
    void arangoFamilyHasMatchingProductsAndFactory() throws Exception {
        assertFamily(ARANGO);
    }

    @Test
    @Timeout(20)
    void neo4jFamilyHasMatchingProductsAndFactory() throws Exception {
        assertFamily(NEO4J);
    }

    @Test
    @Timeout(20)
    void janusGraphFamilyHasMatchingProductsAndFactory() throws Exception {
        assertFamily(JANUSGRAPH);
    }

    @Test
    void familiesDoNotShareProducts() throws Exception {
        List<String> packages = List.of(
                HarborFamilies.factoryClass(HarborFamilies.ARANGO).getPackageName(),
                HarborFamilies.factoryClass(HarborFamilies.NEO4J).getPackageName(),
                HarborFamilies.factoryClass(HarborFamilies.JANUSGRAPH).getPackageName());
        assertThat(packages).as("each database is a family in its own subpackage").doesNotHaveDuplicates();
    }

    // ------------------------------------------------------------------ helpers

    /**
     * The factory lives in a subpackage of graph/ and creates, without contacting the (non-existent) server, new
     * products of its own family on every call, built from exactly its connection parameters.
     */
    private static void assertFamily(String[] arguments) throws Exception {
        try (GraphDatabaseFactory factory = HarborFamilies.newFactory(arguments)) {
            String family = HarborFamilies.familyPackage(factory.getClass());
            assertThat(family).as("%s must be in a subpackage of graph/", factory.getClass().getName()).isNotNull();

            GraphDatabase database = factory.createDatabase();
            GraphStatistics statistics = factory.createStatistics();
            assertThat(factory.createDatabase()).as("createDatabase() must create a new instance")
                    .isNotSameAs(database);
            assertThat(factory.createStatistics()).as("createStatistics() must create a new instance")
                    .isNotSameAs(statistics);
            for (Object product : List.of(database, statistics)) {
                assertThat(product.getClass().getPackageName())
                        .as("%s must be in the family of its factory", product.getClass().getName())
                        .isEqualTo(family);
            }
            assertThat(HarborFamilies.strings(factory, database, statistics))
                    .as("the products must be built from the factory's connection parameters")
                    .contains(arguments);
        }
    }
}
