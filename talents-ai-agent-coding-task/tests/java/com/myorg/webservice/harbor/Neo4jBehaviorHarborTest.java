package com.myorg.webservice.harbor;

import java.net.InetSocketAddress;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.neo4j.harness.Neo4j;
import org.neo4j.harness.Neo4jBuilders;

/**
 * Harbor verifier: {@link GraphDatabaseBehaviorContract} for the Neo4j family, against a Neo4j server that runs
 * inside this test JVM and stops with the tests.
 */
class Neo4jBehaviorHarborTest extends GraphDatabaseBehaviorContract {

    private static Neo4j server;

    @BeforeAll
    static void startServer() {
        server = Neo4jBuilders.newInProcessBuilder().withDisabledServer().build();
    }

    @AfterAll
    static void stopServer() {
        if (server != null) {
            server.close();
        }
    }

    @Override
    String[] serverArguments() {
        return new String[] {server.boltURI().toString(), "neo4j", PASSWORD};
    }

    @Override
    String[] unreachableArguments() {
        return new String[] {"bolt://127.0.0.1:1", "neo4j", PASSWORD};
    }

    @Override
    String[] argumentsFor(String hostAndPort) {
        return new String[] {"bolt://" + hostAndPort, "neo4j", PASSWORD};
    }

    @Override
    InetSocketAddress serverAddress() {
        return new InetSocketAddress(server.boltURI().getHost(), server.boltURI().getPort());
    }
}
