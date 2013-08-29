#
# Base server
#

OPTIONAL=jvm
OPTIONAL=jmx
OPTIONAL=ext
OPTIONAL=resources

LIB=lib/servlet-api-3.1.jar
LIB=lib/jetty-schemas-3.1.jar
LIB=lib/jetty-http-${jetty.version}.jar
LIB=lib/jetty-continuation-${jetty.version}.jar
LIB=lib/jetty-server-${jetty.version}.jar
LIB=lib/jetty-xml-${jetty.version}.jar
LIB=lib/jetty-util-${jetty.version}.jar
LIB=lib/jetty-io-${jetty.version}.jar


# Annotations needs annotations configuration
etc/jetty.xml

INI=threads.min=10
INI=threads.max=200
INI=threads.timeout=60000
INI=#jetty.host=myhost.com
INI=jetty.dump.start=false
INI=jetty.dump.stop=false


