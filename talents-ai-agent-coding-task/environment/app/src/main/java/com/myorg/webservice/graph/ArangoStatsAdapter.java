package com.myorg.webservice.graph;

import com.arangodb.ArangoCursor;
import com.arangodb.ArangoDB;
import com.arangodb.ArangoDatabase;
import com.arangodb.Protocol;
import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.Map;

/** ArangoDB implementation of {@link GraphStats}, over the collections of {@link ArangoDbAdapter}. */
public class ArangoStatsAdapter implements GraphStats {

    private static final System.Logger LOG = System.getLogger(ArangoStatsAdapter.class.getName());

    private final String url;
    private final String user;
    private final String password;
    private final String databaseName;
    private ArangoDB arango;
    private ArangoDatabase database;

    public ArangoStatsAdapter(String url, String user, String password, String databaseName) {
        this.url = url;
        this.user = user;
        this.password = password;
        this.databaseName = databaseName;
    }

    @Override
    public void connect() {
        URI uri = URI.create(url);
        boolean ssl = "https".equalsIgnoreCase(uri.getScheme());
        int port = uri.getPort() > 0 ? uri.getPort() : (ssl ? 443 : 8529);
        arango = new ArangoDB.Builder()
                .host(uri.getHost(), port)
                .useSsl(ssl)
                .user(user)
                .password(password)
                .protocol(Protocol.HTTP_JSON)
                .build();
        RuntimeException last = null;
        for (int attempt = 1; attempt <= 30; attempt++) {
            try {
                ArangoDatabase target = arango.db(databaseName);
                target.getVersion();
                database = target;
                return;
            } catch (RuntimeException e) {
                last = e;
                LOG.log(System.Logger.Level.WARNING, "Failed to connect to ArangoDB (attempt {0}): {1}", attempt,
                        e.getMessage());
                sleep(Duration.ofSeconds(2));
            }
        }
        arango.shutdown();
        arango = null;
        throw new IllegalStateException("Cannot connect to ArangoDB at " + url, last);
    }

    @Override
    public long vertexCount() {
        return count("RETURN LENGTH(vertices)", Map.of());
    }

    @Override
    public long edgeCount() {
        return count("RETURN LENGTH(edges)", Map.of());
    }

    @Override
    public long outDegree(String id) {
        return count("RETURN LENGTH(FOR e IN edges FILTER e._from == @from RETURN 1)",
                Map.of("from", ArangoDbAdapter.VERTICES + "/" + id));
    }

    @Override
    public void close() {
        if (arango != null) {
            arango.shutdown();
            arango = null;
        }
    }

    private long count(String query, Map<String, Object> bindVars) {
        try (ArangoCursor<Long> cursor = database.query(query, Long.class, bindVars)) {
            return cursor.next();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void sleep(Duration interval) {
        try {
            Thread.sleep(interval);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
