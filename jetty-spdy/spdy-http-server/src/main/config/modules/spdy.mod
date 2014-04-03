#
# SPDY Support Module
#

[depend]
ssl
protonego

[lib]
lib/spdy/*.jar

[xml]
etc/jetty-ssl.xml
etc/jetty-spdy.xml

[ini-template]
## SPDY Configuration

# Port for SPDY connections
spdy.port=8443

# SPDY idle timeout in milliseconds
spdy.timeout=30000

# Initial Window Size for SPDY
#spdy.initialWindowSize=65536
