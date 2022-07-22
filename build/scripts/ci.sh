#!/bin/bash

# Represents the command line version of the CI build in Jenkinsfile

mvn clean install -f build
mvn clean install -N
mvn clean install -f jetty-core
mvn clean install -f jetty-integrations
mvn clean install -f jetty-ee10
mvn clean install -f jetty-ee9
mvn clean install -f jetty-ee8
mvn clean install -pl :jetty-home
mvn clean install -f tests
# mvn clean install -f jetty-p2
mvn clean install -f documentation

