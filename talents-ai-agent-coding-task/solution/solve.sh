#!/bin/bash
# Reference solution: refactor the graph package of /app into an Abstract Factory.
#
# abstract-factory.patch:
#   1. GraphDb -> GraphDatabase and GraphStats -> GraphStatistics (the abstract products);
#      GraphDatabaseFactory is the abstract factory creating both.
#   2. Each database is a family in its own subpackage (graph/arango, graph/neo4j, graph/janusgraph):
#      - <Name>Database and <Name>Statistics: the concrete products (the former adapters),
#      - <Name>DatabaseFactory: the concrete factory, constructed with only its database's
#        connection parameters (the ArangoDB database name is now a parameter),
#      - <Name>Connection: the factory's single driver connection, opened by the first product that
#        connects, shared by all products and closed only by the factory. Products may connect from several
#        threads: concurrent connects wait for the one in progress, and the connection's lock is never held
#        while the server is contacted, so that closing the factory stops a connect in progress at once and
#        closes any driver it opened.
#   3. The switch-based GraphDbFactory and GraphStatsFactory are deleted: nothing in the shared package
#      knows a concrete family; which factory to construct is decided by the code that assembles the
#      application.
set -euo pipefail

cd /app

git apply --whitespace=nowarn "$(dirname "$0")/abstract-factory.patch"

mvn -B -q compile
