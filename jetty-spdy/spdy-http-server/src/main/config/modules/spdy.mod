#
# SPDY Support Module
#

[depend]
ssl-protonego

[lib]
lib/spdy/*.jar

[xml]
etc/jetty-ssl.xml
etc/jetty-spdy.xml

[ini-template]
## SPDY Configuration

# Advertised protocols
protonego.protocols=spdy/3,http/1.1
protonego.defaultProtocol=http/1.1

# spdy.initialWindowSize=65536
