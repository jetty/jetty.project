#
# Jetty Proxy module
#

DEPEND=server

LIB=lib/jetty-proxy-${jetty.version}.jar

# Proxy requires configuration
etc/jetty-proxy.xml
