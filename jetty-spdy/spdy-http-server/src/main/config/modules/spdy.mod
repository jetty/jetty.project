#
# SPDY Support Module
#

[depend]
ssl
protonego

[lib]
lib/spdy/*.jar

[xml]
etc/protonego-${protonego}.xml
etc/jetty-ssl.xml
etc/jetty-spdy.xml

[ini-template]
## SPDY Configuration

# Initial Window Size for SPDY
#spdy.initialWindowSize=65536
