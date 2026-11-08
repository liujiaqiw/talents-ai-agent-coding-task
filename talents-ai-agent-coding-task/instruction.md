Refactoring a Graph Database Layer into an Abstract Factory
===========================================================

`/app` is a Java 21 Maven project containing `com.myorg.webservice.graph`, the graph database sub-package
of a webservice:

```bash
cd /app && mvn -B compile
```

It works with three databases, ArangoDB (AQL), Neo4j (Cypher) and JanusGraph (Gremlin), through two products:

- `GraphDb` stores and traverses the graph; `ArangoDbAdapter`, `Neo4jAdapter` and `JanusGraphAdapter` implement it.
- `GraphStats` reads statistics of the same graph; `ArangoStatsAdapter`, `Neo4jStatsAdapter` and
  `JanusGraphStatsAdapter` implement it.
- `GraphDbFactory.create(type, url, user, password)` and `GraphStatsFactory.create(...)` pick the implementation
  with a `switch` over `type`. Both know every database, must be kept in sync, can be combined into mismatched pairs
  (an ArangoDB `GraphDb` with Neo4j `GraphStats`), force all databases into one parameter list, and every
  implementation opens its own connection.

Refactor the package into the **Abstract Factory** design pattern described in
`/app/docs/design-pattern-abstract-factory.md`.

Requirements
------------

The verifier runs the products against real databases where it can, and otherwise checks that their database
code is unchanged.

**Abstractions.** Directly in `com.myorg.webservice.graph`:

- `GraphDatabase` replaces `GraphDb` with the same operations, which keep behaving as before.
- `GraphStatistics` replaces `GraphStats` with the same operations (`connect()`, `long vertexCount()`,
  `long edgeCount()`, `long outDegree(String id)`, `close()`), which keep behaving as before.
- `GraphDatabaseFactory`, the abstract factory, `extends AutoCloseable` and has exactly these operations:
  `GraphDatabase createDatabase()`, `GraphStatistics createStatistics()` and `void close()`.

**Families.** Each database is a family with its concrete products and one concrete factory, which has a public
constructor taking exactly the connection parameters of its database, all `String`s, in this order:

| Database | Factory constructor parameters |
| --- | --- |
| ArangoDB | `url`, `user`, `password`, `database` (the name of the database, created if it is missing) |
| Neo4j | `uri`, `user`, `password` |
| JanusGraph | `url` |

You choose the names of the families and of their classes. Classes directly in `com.myorg.webservice.graph`
only know the abstractions, never a family; each family lives in its own subpackage, which is the only place that
uses its database driver (`com.arangodb`, `org.neo4j.driver`, `org.apache.tinkerpop`); and families do not use each
other's classes, so adding a database means adding a subpackage without changing any existing class. Each concrete
`GraphDatabase` keeps the public `connect(int attempts, Duration interval)` of the implementation it comes from.

**Connection lifecycle.**

- Creating a factory or a product does not contact the database.
- A factory holds exactly one driver connection (a `com.arangodb.ArangoDB`, an `org.neo4j.driver.Driver` or a
  Gremlin `Cluster`), shared by all products it creates. The first `connect()` of any of its products opens it; a
  product of the factory can be used once the connection is open, without a `connect()` of its own.
- Closing a product releases only that product: afterwards every operation of it except `close()` throws
  `IllegalStateException`, while the factory's other products keep working.
- Closing the factory closes its connection: afterwards every operation of its products except `close()`, including
  `connect()`, throws `IllegalStateException`, and so do `createDatabase()` and `createStatistics()`.
- `close()` of products and factories is idempotent. Factories, even of the same database, are independent.
- The webservice uses a factory and its products from several threads at once. Concurrent `connect()` calls of
  products of one factory open exactly one connection, which all of them then use.
- Closing the factory never waits for a `connect()` in progress, not even one still in the server handshake: that
  `connect()` stops retrying and throws `IllegalStateException`, even when its connection succeeds after the close,
  and no connection to the database stays open.

**Build.** The verifier compiles only the Java sources of `com.myorg.webservice.graph` and its subpackages under
`src/main/java`, with its own build configuration and the dependencies the project has now; other sources and
changes to `pom.xml` or other build files are not used.

**Cleanup.** No `GraphDb`, `GraphStats`, `GraphDbFactory`, `GraphStatsFactory` or `*Adapter` / `*Impl` types remain.
Do not use the `var` keyword, and keep every line of the Java sources at 120 characters or fewer.
