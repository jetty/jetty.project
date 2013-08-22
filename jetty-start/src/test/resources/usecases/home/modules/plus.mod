#
# Jetty Proxy module
#

DEPEND=server

LIB=lib/jetty-plus-${jetty.version}.jar

# Plus requires configuration
etc/jetty-plus.xml
