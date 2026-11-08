package com.myorg.webservice.graph;

import com.arangodb.ArangoCollection;
import com.arangodb.ArangoCursor;
import com.arangodb.ArangoDB;
import com.arangodb.ArangoDatabase;
import com.arangodb.Protocol;
import com.arangodb.entity.CollectionType;
import com.arangodb.entity.EdgeDefinition;
import com.arangodb.model.CollectionCreateOptions;
import com.arangodb.model.DocumentCreateOptions;
import com.arangodb.model.OverwriteMode;
import java.net.URI;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * ArangoDB implementation of {@link GraphDb}.
 *
 * <p>Vertices are documents in the {@value #VERTICES} collection (the vertex ID is the document key), edges are
 * documents in the {@value #EDGES} edge collection, and the named graph {@value #GRAPH} joins the two for traversals.
 */
public class ArangoDbAdapter implements GraphDb {

    static final String VERTICES = "vertices";
    static final String EDGES = "edges";
    static final String GRAPH = "graph";

    private static final System.Logger LOG = System.getLogger(ArangoDbAdapter.class.getName());

    private final String url;
    private final String user;
    private final String password;
    private final String databaseName;
    private ArangoDB arango;
    private ArangoDatabase database;

    public ArangoDbAdapter(String url, String user, String password, String databaseName) {
        this.url = url;
        this.user = user;
        this.password = password;
        this.databaseName = databaseName;
    }

    @Override
    public void connect() {
        connect(30, Duration.ofSeconds(2));
    }

    /**
     * Connects to the server and creates the database if it is missing.
     *
     * @param attempts number of attempts; the server may still be starting
     * @param interval pause between attempts
     * @throws IllegalStateException if the server cannot be reached
     */
    public void connect(int attempts, Duration interval) {
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
        for (int attempt = 1; attempt <= attempts; attempt++) {
            try {
                ArangoDatabase target = arango.db(databaseName);
                if (!target.exists()) {
                    arango.createDatabase(databaseName);
                }
                target.getVersion();
                database = target;
                LOG.log(System.Logger.Level.INFO, "Connected to ArangoDB {0}/{1}", url, databaseName);
                return;
            } catch (RuntimeException e) {
                last = e;
                LOG.log(System.Logger.Level.WARNING, "Failed to connect to ArangoDB (attempt {0}): {1}", attempt,
                        e.getMessage());
                sleep(interval);
            }
        }
        arango.shutdown();
        arango = null;
        throw new IllegalStateException("Cannot connect to ArangoDB at " + url, last);
    }

    @Override
    public void ensureSchema() {
        if (!database.collection(VERTICES).exists()) {
            database.createCollection(VERTICES);
        }
        if (!database.collection(EDGES).exists()) {
            database.createCollection(EDGES, new CollectionCreateOptions().type(CollectionType.EDGES));
        }
        if (!database.graph(GRAPH).exists()) {
            database.createGraph(GRAPH, List.of(new EdgeDefinition().collection(EDGES).from(VERTICES).to(VERTICES)));
        }
    }

    @Override
    public void upsertVertex(String id, Map<String, Object> properties) {
        Map<String, Object> document = new HashMap<>(properties);
        document.put("_key", id);
        database.collection(VERTICES).insertDocument(document,
                new DocumentCreateOptions().overwriteMode(OverwriteMode.update));
    }

    @Override
    public void upsertEdge(String fromId, String toId, Map<String, Object> properties) {
        Map<String, Object> bindVars = new HashMap<>();
        bindVars.put("from", VERTICES + "/" + fromId);
        bindVars.put("to", VERTICES + "/" + toId);
        bindVars.put("properties", properties);
        database.query("""
                UPSERT {_from: @from, _to: @to}
                INSERT MERGE(@properties, {_from: @from, _to: @to})
                UPDATE @properties
                IN edges""", Void.class, bindVars);
    }

    @Override
    @SuppressWarnings("unchecked")
    public Optional<Map<String, Object>> findVertex(String id) {
        ArangoCollection vertices = database.collection(VERTICES);
        Map<String, Object> document = vertices.getDocument(id, Map.class);
        if (document == null) {
            return Optional.empty();
        }
        Map<String, Object> properties = new HashMap<>(document);
        properties.keySet().removeIf(key -> key.startsWith("_"));
        return Optional.of(properties);
    }

    @Override
    public List<String> neighbors(String id, int depth) {
        if (depth < 1) {
            return List.of();
        }
        Map<String, Object> bindVars = Map.of("start", VERTICES + "/" + id, "depth", depth);
        try (ArangoCursor<String> cursor = database.query("""
                FOR v, e, p IN 1..@depth OUTBOUND @start GRAPH 'graph'
                    OPTIONS {order: 'bfs', uniqueVertices: 'global'}
                    FILTER v._id != @start
                    SORT LENGTH(p.edges), v._key
                    RETURN v._key""", String.class, bindVars)) {
            return cursor.asListRemaining();
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public void close() {
        if (arango != null) {
            arango.shutdown();
            arango = null;
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
