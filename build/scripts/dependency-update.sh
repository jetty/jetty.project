#!/usr/bin/env bash

echo "# This script is meant to be run from the root of the project"
echo "[jetty.project-12.0.x]$ build/scripts/dependency-update.sh"

PWD=$(pwd)

mvn -N -B -Pupdate-dependencies validate -Dmaven.build.cache.enabled=false

pushd jetty-core
mvn -N -B -Pupdate-dependencies-core validate -Dmaven.build.cache.enabled=false
popd

pushd jetty-ee10
mvn -N -B -Pupdate-dependencies-ee10 validate -Dmaven.build.cache.enabled=false
popd

pushd jetty-ee9
mvn -N -B -Pupdate-dependencies-ee9 validate -Dmaven.build.cache.enabled=false
popd

pushd jetty-ee8
mvn -N -B -Pupdate-dependencies-ee8 validate -Dmaven.build.cache.enabled=false
popd
