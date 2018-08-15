#!/usr/bin/env bash

set -ex
set -o pipefail

JAVA_HOME="${JAVA_HOME:-${JAVA_1_8_HOME}}"
export JAVA_HOME
PATH="${MAVEN_HOME:-${MAVEN_3_5_0_HOME}}/bin:${JAVA_HOME}/bin:${PATH}"
export PATH

MVN_REPO_LOCAL=$HOME/.m2/repository${M2_REPO_SUFFIX}
declare -a MVN_ARGS=(--batch-mode --fail-at-end -Dmaven.repo.local="${MVN_REPO_LOCAL}")
mvn -version
# install the tar.gz for native stuff locally, because the test module needs it at compile scope.
# install the docs manual, because the top level module needs it at compile scope.
mvn "${MVN_ARGS[@]}" -DskipTests -am -pl server/native -pl docs install
mvn "${MVN_ARGS[@]}" test
