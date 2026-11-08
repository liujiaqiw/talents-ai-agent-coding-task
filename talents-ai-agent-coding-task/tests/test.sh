#!/bin/bash
# Verifies the Abstract Factory refactoring of /app. No server process is started: the databases of the hidden
# tests run inside the test JVM and stop with it, so every step runs to completion.
#   1. builds the agent's Java sources with the hidden JUnit tests from /tests/java and runs the tests, in a fresh
#      directory with the verifier's own build file, Maven settings and dependency cache, so that nothing the agent
#      can change in /app or the image (pom.xml, .mvn/, ~/.m2) decides how the tests run,
#   2. runs /tests/test_outputs.py (source checks and the report of step 1).

mkdir -p /logs/verifier
echo 0 > /logs/verifier/reward.txt

# --- hidden tests ------------------------------------------------------------------------------
BUILD=$(mktemp -d /tmp/harbor-verify.XXXXXX)
mkdir -p "$BUILD/src/main/java" "$BUILD/src/test/java"
# Only the graph package and its subpackages: a class the agent writes anywhere else (e.g. a fake
# org.assertj.core.api.Assertions) would shadow the libraries the hidden tests rely on.
if [ -d /app/src/main/java/com/myorg/webservice/graph ]; then
  (cd /app/src/main/java && find com/myorg/webservice/graph -name '*.java' ! -name module-info.java \
    -exec cp --parents {} "$BUILD/src/main/java/" \;)
fi
cp -r /tests/java/. "$BUILD/src/test/java/"
cp /tests/pom.xml "$BUILD/pom.xml"

# MAVEN_SKIP_RC: ignore ~/.mavenrc and /etc/mavenrc, which the agent could have written.
env -u MAVEN_OPTS -u MAVEN_ARGS MAVEN_SKIP_RC=true mvn -B -f "$BUILD/pom.xml" \
  -s /tests/maven-settings.xml -gs /tests/maven-settings.xml \
  -Dmaven.repo.local="$BUILD/repository" -Dsurefire.timeout=600 \
  test > /logs/verifier/hidden-tests.log 2>&1
HIDDEN_TESTS_EXIT=$?
echo $HIDDEN_TESTS_EXIT > /logs/verifier/hidden-tests.exit
rm -rf /logs/verifier/surefire-hidden
cp -r "$BUILD/target/surefire-reports" /logs/verifier/surefire-hidden 2>/dev/null \
  || mkdir -p /logs/verifier/surefire-hidden
rm -rf "$BUILD"

# --- checks ------------------------------------------------------------------------------------
curl -LsSf https://astral.sh/uv/0.9.7/install.sh | sh > /dev/null
source "$HOME/.local/bin/env"
# Maven's exit status reaches the checks through the environment, so that nothing written to disk while the agent's
# code runs can fake it.
HIDDEN_TESTS_EXIT=$HIDDEN_TESTS_EXIT uvx \
  --python 3.12 \
  --with pytest==8.4.1 \
  --with pytest-json-ctrf==0.3.5 \
  pytest -p no:cacheprovider --ctrf /logs/verifier/ctrf.json /tests/test_outputs.py -rA
status=$?

if [ $status -eq 0 ]; then
  echo 1 > /logs/verifier/reward.txt
else
  echo 0 > /logs/verifier/reward.txt
fi
