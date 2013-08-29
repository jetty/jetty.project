
DEPEND=ssl
DEPEND=npn

LIB=lib/spdy/*.jar

etc/jetty-ssl.xml
etc/jetty-spdy.xml

INI=spdy.port=8443
INI=spdy.timeout=30000
INI=#spdy.initialWindowSize=65536