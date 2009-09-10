#!/bin/bash

M2REPO=$HOME/.m2/repository

function jettydep()
{
    echo "$M2REPO/org/eclipse/jetty/$1/7.0.1-SNAPSHOT/$1-7.0.1-SNAPSHOT.jar"
}

function slf4jdep()
{
    echo "$M2REPO/org/slf4j/$1/1.5.6/$1-1.5.6.jar"
}

CP="target/classes:target/test-classes"
CP="$CP:"`jettydep "jetty-util"`
CP="$CP:"`jettydep "jetty-io"`
CP="$CP:"`jettydep "jetty-http"`
CP="$CP:"`jettydep "jetty-xml"`
CP="$CP:"`jettydep "jetty-server"`
CP="$CP:"`jettydep "jetty-security"`
CP="$CP:"`jettydep "jetty-servlet"`
CP="$CP:"`jettydep "jetty-webapp"`
CP="$CP:"`jettydep "jetty-deploy"`
CP="$CP:"`jettydep "jetty-continuation"`
CP="$CP:$M2REPO/javax/servlet/servlet-api/2.5/servlet-api-2.5.jar"
CP="$CP:"`slf4jdep "slf4j-api"`
CP="$CP:"`slf4jdep "jcl-over-slf4j"`
CP="$CP:"`slf4jdep "log4j-over-slf4j"`
CP="$CP:$M2REPO/junit/junit/3.8.2/junit-3.8.2.jar"

TESTBASEDIR=`pwd`
EXTRA=""
# EXTRA="-Xdebug -Xnoagent -Djava.compiler=NONE -Xrunjdwp:transport=dt_socket,server=y,suspend=y,address=5005"

java -cp $CP $EXTRA \
  "-Dbasedir=$TESTBASEDIR" \
  junit.textui.TestRunner \
  org.eclipse.jetty.logging.CentralizedLoggingTest

