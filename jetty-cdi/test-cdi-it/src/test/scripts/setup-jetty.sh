#!/usr/bin/env bash

JAVA_HOME=$1
JETTY_HOME=$2
JETTY_BASE=$3

echo \${java.home}  : $JAVA_HOME
echo \${jetty.home} : $JETTY_HOME
echo \${jetty.base} : $JETTY_BASE

cd "$JETTY_BASE"

"$JAVA_HOME/bin/java" -jar "$JETTY_HOME/start.jar" \
    --approve-all-licenses \
    --add-to-start=deploy,http,annotations,websocket,cdi,logging

"$JAVA_HOME/bin/java" -jar "$JETTY_HOME/start.jar" \
    --version

