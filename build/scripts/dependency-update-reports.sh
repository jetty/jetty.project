#!/usr/bin/env bash

echo "# This script is meant to be run from the root of the project"
echo "[jetty.project-12.0.0x]$ build/scripts/dependency-updates-report.sh"

PWD=$(pwd)
REPORT_OUTPUT_DIR=$PWD/reports/dependency-update-reports/

if [ -d $REPORT_OUTPUT_DIR ] ; then
    rm -rf $REPORT_OUTPUT_DIR/*
fi

mkdir -p $REPORT_OUTPUT_DIR

echo "HTML Reports can be found in $REPORT_OUTPUT_DIR"

mvn -N -B -Pdependency-updates-reports validate -Dmaven.build.cache.enabled=false

cp -Rv target/reports/* $REPORT_OUTPUT_DIR
mv $REPORT_OUTPUT_DIR/dependency-updates-aggregate-report.html $REPORT_OUTPUT_DIR/dependency-updates-report-root.html

pushd jetty-core
mvn -B -Pdependency-updates-reports validate -Dmaven.build.cache.enabled=false
cp target/reports/dependency-updates-aggregate-report.html $REPORT_OUTPUT_DIR/dependency-updates-report-core.html
popd

pushd jetty-ee10
mvn -B -Pdependency-updates-reports validate -Dmaven.build.cache.enabled=false
cp target/reports/dependency-updates-aggregate-report.html $REPORT_OUTPUT_DIR/dependency-updates-report-ee10.html
popd

pushd jetty-ee9
mvn -B -Pdependency-updates-reports validate -Dmaven.build.cache.enabled=false
cp target/reports/dependency-updates-aggregate-report.html $REPORT_OUTPUT_DIR/dependency-updates-report-ee9.html
popd

pushd jetty-ee8
mvn -B -Pdependency-updates-reports validate -Dmaven.build.cache.enabled=false
cp target/reports/dependency-updates-aggregate-report.html $REPORT_OUTPUT_DIR/dependency-updates-report-ee8.html
popd

echo "HTML Reports can be found in $REPORT_OUTPUT_DIR"
