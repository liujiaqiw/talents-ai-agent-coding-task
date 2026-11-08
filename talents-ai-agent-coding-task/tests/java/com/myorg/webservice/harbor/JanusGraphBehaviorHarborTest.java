package com.myorg.webservice.harbor;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import org.apache.tinkerpop.gremlin.server.GremlinServer;
import org.apache.tinkerpop.gremlin.server.Settings;
import org.apache.tinkerpop.gremlin.server.util.ServerGremlinExecutor;
import org.janusgraph.core.JanusGraph;
import org.janusgraph.core.JanusGraphFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;

/**
 * Harbor verifier: {@link GraphDatabaseBehaviorContract} for the JanusGraph family, against an in-memory JanusGraph
 * served by a Gremlin server that runs inside this test JVM and stops with the tests.
 */
class JanusGraphBehaviorHarborTest extends GraphDatabaseBehaviorContract {

    private static JanusGraph graph;
    private static GremlinServer server;
    private static int port;

    @BeforeAll
    static void startServer() throws Exception {
        graph = JanusGraphFactory.build().set("storage.backend", "inmemory").open();
        port = freePort();
        Settings settings = new Settings();
        settings.host = "127.0.0.1";
        settings.port = port;
        server = new GremlinServer(settings);
        ServerGremlinExecutor executor = server.getServerGremlinExecutor();
        executor.getGraphManager().putGraph("graph", graph);
        executor.getGraphManager().putTraversalSource("g", graph.traversal());
        server.start().join();
    }

    @AfterAll
    static void stopServer() {
        if (server != null) {
            server.stop().join();
        }
        if (graph != null) {
            graph.close();
        }
    }

    @Override
    String[] serverArguments() {
        return new String[] {"ws://127.0.0.1:" + port + "/gremlin"};
    }

    @Override
    String[] unreachableArguments() {
        return new String[] {"ws://127.0.0.1:1/gremlin"};
    }

    @Override
    String[] argumentsFor(String hostAndPort) {
        return new String[] {"ws://" + hostAndPort + "/gremlin"};
    }

    @Override
    InetSocketAddress serverAddress() {
        return new InetSocketAddress("127.0.0.1", port);
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
