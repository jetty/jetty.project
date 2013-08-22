#
# JNDI Support
#

DEPEND=server
DEPEND=plus

LIB=lib/jetty-jndi-${jetty.version}.jar
LIB=lib/jndi/*.jar

# Annotations needs annotations configuration
etc/jetty-server.xml
