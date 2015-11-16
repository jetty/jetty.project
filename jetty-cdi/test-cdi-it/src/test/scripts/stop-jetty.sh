#!/usr/bin/env bash

JAVA_HOME=$1
JETTY_HOME=$2
JETTY_BASE=$3

cd "$JETTY_BASE"
"$JAVA_HOME/bin/java" -jar "$JETTY_HOME/start.jar" \
 --stop STOP.PORT=58181 STOP.KEY=it


