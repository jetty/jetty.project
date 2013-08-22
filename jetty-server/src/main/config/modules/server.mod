#
# Base server
#

DEPEND=base

LIB=lib/servlet-api-3.1.jar
LIB=lib/jetty-schemas-3.1.jar
LIB=lib/jetty-http-${jetty.version}.jar
LIB=lib/jetty-continuation-${jetty.version}.jar
LIB=lib/jetty-server-${jetty.version}.jar

# Annotations needs annotations configuration
etc/jetty.xml
