"""Verifier for the Abstract Factory refactoring of the graph package in /app.

tests/test.sh runs the hidden JUnit tests in /tests/java against /app before running this file. The
checks here cover:

- source structure of the graph package (comments are stripped first, so Javadoc links do not count
  as dependencies). The agent chooses the names of the families and their classes, so each family is
  found by the database driver it uses: the subpackage of graph/ whose code uses that driver,
- the hidden JUnit tests (read from the surefire reports): the Abstract Factory structure, the
  behaviour and connection lifecycle of the Neo4j and JanusGraph products against databases that run
  inside the test JVM, and the lifecycle of the ArangoDB products as far as it needs no server,
- for ArangoDB, which cannot run inside the test JVM, that the moved code keeps its queries and driver
  calls.
"""

import os
import re
import xml.etree.ElementTree as ET
from pathlib import Path

import pytest

APP = Path("/app")
GRAPH = APP / "src" / "main" / "java" / "com/myorg/webservice" / "graph"
VERIFIER = Path("/logs/verifier")
HIDDEN_REPORTS = VERIFIER / "surefire-hidden"

DRIVERS = {
    "ArangoDB": "com.arangodb",
    "Neo4j": "org.neo4j.driver",
    "JanusGraph": "org.apache.tinkerpop",
}

ABSTRACTIONS = ("GraphDatabase", "GraphStatistics", "GraphDatabaseFactory")

HIDDEN_TESTS = {
    "com.myorg.webservice.harbor.AbstractFactoryHarborTest": [
        "abstractFactoryCreatesBothAbstractProducts",
        "productsKeepTheirOperations",
        "arangoFamilyHasMatchingProductsAndFactory",
        "neo4jFamilyHasMatchingProductsAndFactory",
        "janusGraphFamilyHasMatchingProductsAndFactory",
        "familiesDoNotShareProducts",
    ],
}

# GraphDatabaseBehaviorContract, run once per family.
BEHAVIOR_TESTS = [
    "ensureSchemaIsIdempotent",
    "findVertexReturnsTheStoredProperties",
    "findVertexOfUnknownIdIsEmpty",
    "upsertVertexMergesPropertiesIntoTheExistingVertex",
    "writesAreStoredOnTheServer",
    "neighborsAreOrderedByHopsAndThenById",
    "neighborsStopAtTheGivenDepth",
    "neighborsFollowOutboundEdgesOnly",
    "upsertEdgeTwiceKeepsOneEdge",
    "neighborsOfUnknownVertexAreEmpty",
    "neighborsWithDepthBelowOneAreEmpty",
    "statisticsCountWhatTheDatabaseStores",
    "outDegreeCountsOutboundEdges",
    "statisticsUseTheConnectionOpenedByTheDatabase",
    "productsOfOneFactoryShareOneDriver",
    "closingAProductKeepsTheOtherProductsWorking",
    "closedProductRejectsEveryOperation",
    "closingTheFactoryClosesItsConnectionForAllProducts",
    "factoriesOfTheSameFamilyAreIndependent",
    "closeWithoutConnectIsHarmless",
    "connectFailsWhenTheServerIsUnreachable",
    "closeDoesNotWaitForAConnectInProgress",
    "closeDuringTheHandshakeLeavesNoConnectionOpen",
    "concurrentConnectsOpenOneConnection",
]
for family in ("Neo4j", "JanusGraph"):
    HIDDEN_TESTS[f"com.myorg.webservice.harbor.{family}BehaviorHarborTest"] = BEHAVIOR_TESTS
HIDDEN_TESTS["com.myorg.webservice.harbor.ArangoConnectionHarborTest"] = [
    "closeWithoutConnectIsHarmless",
    "connectFailsWhenTheServerIsUnreachable",
    "closedProductRejectsEveryOperation",
    "closedFactoryRejectsNewProducts",
    "productsOfAClosedFactoryRejectEveryOperation",
    "closeDoesNotWaitForAConnectInProgress",
    "closeDuringTheHandshakeLeavesNoConnectionOpen",
]

# What the ArangoDB family must keep from the original implementations (whitespace in queries does not
# matter): its AQL queries and the names of its collections and graph...
ARANGO_LITERALS = {
    "UPSERT {_from: @from, _to: @to} INSERT MERGE(@properties, {_from: @from, _to: @to}) UPDATE @properties IN edges",
    "FOR v, e, p IN 1..@depth OUTBOUND @start GRAPH 'graph' OPTIONS {order: 'bfs', uniqueVertices: 'global'} "
    "FILTER v._id != @start SORT LENGTH(p.edges), v._key RETURN v._key",
    "RETURN LENGTH(vertices)",
    "RETURN LENGTH(edges)",
    "RETURN LENGTH(FOR e IN edges FILTER e._from == @from RETURN 1)",
    "vertices",
    "edges",
    "graph",
    "_key",
}
# ...and the driver methods and constants it uses.
ARANGO_MEMBERS = {
    "Builder", "host", "useSsl", "user", "password", "protocol", "HTTP_JSON", "build", "db", "exists",
    "createDatabase", "getVersion", "shutdown", "collection", "createCollection", "EDGES", "graph", "createGraph",
    "insertDocument", "overwriteMode", "update", "query", "getDocument", "startsWith", "asListRemaining",
}


# ---------------------------------------------------------------------------------------------
# helpers
# ---------------------------------------------------------------------------------------------


def strip_java_comments(source: str) -> str:
    """Removes // and /* */ comments while keeping string, char and text block literals."""
    out = []
    i = 0
    n = len(source)
    while i < n:
        if source.startswith('"""', i):
            end = source.find('"""', i + 3)
            end = n if end < 0 else end + 3
            out.append(source[i:end])
            i = end
        elif source[i] in "\"'":
            quote = source[i]
            j = i + 1
            while j < n and source[j] != quote and source[j] != "\n":
                j += 2 if source[j] == "\\" else 1
            out.append(source[i : j + 1])
            i = j + 1
        elif source.startswith("//", i):
            end = source.find("\n", i)
            i = n if end < 0 else end
        elif source.startswith("/*", i):
            end = source.find("*/", i + 2)
            i = n if end < 0 else end + 2
        else:
            out.append(source[i])
            i += 1
    return "".join(out)


def java_files(root: Path) -> list[Path]:
    return sorted(root.rglob("*.java"))


def code(path: Path) -> str:
    return strip_java_comments(path.read_text(encoding="utf-8"))


def relative(path: Path) -> str:
    return str(path.relative_to(GRAPH))


def subpackage(path: Path) -> str | None:
    """The direct subpackage of graph/ that holds the file, or None for files directly in graph/."""
    parts = path.relative_to(GRAPH).parts
    return parts[0] if len(parts) > 1 else None


def subpackages() -> list[str]:
    return sorted(path.name for path in GRAPH.iterdir() if path.is_dir()) if GRAPH.is_dir() else []


def type_names(root: Path, recursive: bool = True) -> set[str]:
    """Names of the top-level and nested types declared in the Java files under root."""
    files = java_files(root) if recursive else sorted(root.glob("*.java"))
    names = set()
    for path in files:
        names.update(re.findall(r"\b(?:class|interface|enum|record)\s+([A-Z]\w*)", code(path)))
    return names


def references_package(text: str, package: str) -> bool:
    return re.search(rf"\bgraph\.{re.escape(package)}\b", text) is not None


def references_type(text: str, names: set[str]) -> bool:
    return any(re.search(rf"\b{re.escape(name)}\b", text) for name in names)


def read_reports(directory: Path) -> dict[str, dict[str, str]]:
    """{suite: {test name: "passed" | "failed" | "skipped"}} from surefire XML reports."""
    results: dict[str, dict[str, str]] = {}
    for report in sorted(directory.glob("TEST-*.xml")):
        root = ET.parse(report).getroot()
        for case in root.iter("testcase"):
            outcome = "passed"
            for child in case:
                if child.tag in ("failure", "error"):
                    outcome = "failed"
                elif child.tag == "skipped":
                    outcome = "skipped"
            name = re.sub(r"\(.*\)$", "", case.get("name", ""))
            results.setdefault(case.get("classname", ""), {})[name] = outcome
    return results


# ---------------------------------------------------------------------------------------------
# source structure
# ---------------------------------------------------------------------------------------------


def test_legacy_types_removed():
    offenders = []
    for path in java_files(GRAPH):
        if re.search(r"\b(GraphDb\w*|GraphStats\w*|\w+Adapter|\w+Impl)\b", code(path)):
            offenders.append(relative(path))
    assert not offenders, f"legacy GraphDb* / GraphStats* / *Adapter / *Impl names remain in: {offenders}"


def test_abstractions_are_in_the_graph_package():
    missing = [name for name in ABSTRACTIONS if not (GRAPH / f"{name}.java").is_file()]
    assert not missing, f"missing abstractions directly in graph/: {missing}"


@pytest.mark.parametrize("database,driver", DRIVERS.items())
def test_each_driver_is_used_in_exactly_one_family(database, driver):
    users = [path for path in java_files(GRAPH) if driver in code(path)]
    packages = {subpackage(path) for path in users}
    assert users, f"no class uses the {database} driver ({driver})"
    assert None not in packages, (
        f"the {database} driver may only be used inside its family's subpackage, not directly in graph/: "
        f"{[relative(path) for path in users if subpackage(path) is None]}"
    )
    assert len(packages) == 1, f"the {database} driver is used in several subpackages: {sorted(packages)}"


def test_families_are_separate_subpackages():
    families = {}
    for database, driver in DRIVERS.items():
        families[database] = {subpackage(path) for path in java_files(GRAPH) if driver in code(path)}
    packages = [package for found in families.values() for package in found]
    assert len(packages) == len(set(packages)) == len(DRIVERS), (
        f"each database must be a family in its own subpackage of graph/, found: {families}"
    )


def test_abstract_graph_package_knows_no_concrete_family():
    family_types = type_names(GRAPH) - type_names(GRAPH, recursive=False)
    offenders = []
    for path in sorted(GRAPH.glob("*.java")):
        text = code(path)
        if any(references_package(text, package) for package in subpackages()) or references_type(text, family_types):
            offenders.append(path.name)
        elif any(driver in text for driver in DRIVERS.values()):
            offenders.append(path.name)
    assert not offenders, (
        "classes directly in graph/ may only know the abstractions, but these reference a concrete family or a "
        f"database driver: {offenders}"
    )


def test_families_do_not_depend_on_each_other():
    offenders = []
    for package in subpackages():
        others = [other for other in subpackages() if other != package]
        other_types = set().union(*(type_names(GRAPH / other) for other in others)) if others else set()
        other_types -= type_names(GRAPH / package) | set(ABSTRACTIONS)
        for path in java_files(GRAPH / package):
            text = code(path)
            if any(references_package(text, other) for other in others) or references_type(text, other_types):
                offenders.append(relative(path))
    assert not offenders, f"a family must not use another family's classes: {offenders}"


def string_literals(java: str) -> set[str]:
    """String and text block literals of comment-free Java code, with whitespace runs collapsed."""
    literals = re.findall(r'"""(.*?)"""|"((?:[^"\\\n]|\\.)*)"', java, flags=re.DOTALL)
    return {" ".join((block or string).split()) for block, string in literals}


def used_members(java: str) -> set[str]:
    """Methods called on an object or class, and fields or constants read from a class (e.g.
    OverwriteMode.update), in comment-free Java code."""
    calls = re.findall(r"\.\s*(\w+)\s*\(", java)
    constants = re.findall(r"\b[A-Z]\w*\.(\w+)\b(?!\s*\()", java)
    return set(calls) | set(constants)


def test_arango_database_code_unchanged():
    """ArangoDB cannot run inside the test JVM, so its behaviour is checked through its code: the family
    that uses the ArangoDB driver keeps the queries, names, driver methods and constants of the original
    ArangoDbAdapter and ArangoStatsAdapter. Renaming, moving code between classes and reformatting do
    not matter."""
    packages = {subpackage(path) for path in java_files(GRAPH) if DRIVERS["ArangoDB"] in code(path)}
    packages.discard(None)
    assert len(packages) == 1, f"expected one subpackage of graph/ using the ArangoDB driver, found {packages}"
    family = "\n".join(code(path) for path in java_files(GRAPH / packages.pop()))
    missing_literals = sorted(ARANGO_LITERALS - string_literals(family))
    missing_members = sorted(ARANGO_MEMBERS - used_members(family))
    assert not missing_literals and not missing_members, (
        "the ArangoDB family must keep the database code of ArangoDbAdapter and ArangoStatsAdapter; "
        f"missing string literals: {missing_literals}, missing methods and constants: {missing_members}"
    )


def test_no_var_keyword():
    offenders = [
        relative(path)
        for path in java_files(GRAPH)
        if re.search(r"(^|[(;{}\s])var\s+\w+\s*[=:]", code(path), re.MULTILINE)
    ]
    assert not offenders, f"`var` must not be used: {offenders}"


def test_line_length():
    long_lines = []
    for path in java_files(GRAPH):
        for number, line in enumerate(path.read_text(encoding="utf-8").splitlines(), start=1):
            if len(line) > 120:
                long_lines.append(f"{relative(path)}:{number} ({len(line)} chars)")
    assert not long_lines, f"lines longer than 120 characters: {long_lines}"


# ---------------------------------------------------------------------------------------------
# test suites
# ---------------------------------------------------------------------------------------------


def test_hidden_tests_compiled():
    exit_code = (VERIFIER / "hidden-tests.exit").read_text().strip()
    assert read_reports(HIDDEN_REPORTS), (
        f"the hidden tests did not compile or run (exit {exit_code}); see /logs/verifier/hidden-tests.log"
    )


def test_hidden_tests_passed():
    """The verifier's Maven run itself must succeed, i.e. every hidden test passed. Its exit status comes
    from tests/test.sh through the environment, so no report or file written during the run can fake it."""
    exit_code = os.environ.get("HIDDEN_TESTS_EXIT")
    assert exit_code == "0", f"the hidden test run failed (exit {exit_code}); see /logs/verifier/hidden-tests.log"


@pytest.mark.parametrize(
    "suite,name",
    [(suite, name) for suite, names in HIDDEN_TESTS.items() for name in names],
)
def test_hidden(suite, name):
    outcome = read_reports(HIDDEN_REPORTS).get(suite, {}).get(name)
    assert outcome == "passed", f"{suite}.{name}: {outcome or 'did not run'} (see hidden-tests.log)"
