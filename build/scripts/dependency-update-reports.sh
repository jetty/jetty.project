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

mvn -N -Pdependency-updates-reports validate

cp -Rv target/site/* $REPORT_OUTPUT_DIR
mv $REPORT_OUTPUT_DIR/dependency-updates-aggregate-report.html $REPORT_OUTPUT_DIR/dependency-updates-report-root.html

pushd jetty-ee10
mvn -Pdependency-updates-reports validate
cp target/site/dependency-updates-aggregate-report.html $REPORT_OUTPUT_DIR/dependency-updates-report-ee10.html

pushd jetty-ee9
mvn -Pdependency-updates-reports validate
cp target/site/dependency-updates-aggregate-report.html $REPORT_OUTPUT_DIR/dependency-updates-report-ee9.html

pushd jetty-ee8
mvn -Pdependency-updates-reports validate
cp target/site/dependency-updates-aggregate-report.html $REPORT_OUTPUT_DIR/dependency-updates-report-ee8.html

echo "HTML Reports can be found in $REPORT_OUTPUT_DIR"
