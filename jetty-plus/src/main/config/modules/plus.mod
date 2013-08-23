#
# Jetty Proxy module
#

DEPEND=server
DEPEND=security
DEPEND=jndi

LIB=lib/jetty-plus-${jetty.version}.jar

# Plus requires configuration
etc/jetty-plus.xml
