#
# SPDY Support Module
#

[depend]
ssl
protonego/${proto.nego}

[lib]
lib/spdy/*.jar

[xml]
etc/jetty-ssl.xml
etc/jetty-spdy.xml

[ini-template]
## SPDY Configuration
# Protocol Negotiation Implementation
#  choices are:
#    'npn'  : original implementation for SPDY (now deprecated)
#    'alpn' : replacement for NPN, in use by current SPDY implementations
#             and the future HTTP/2 spec
#  Note: java 1.8+ are ALPN only.
proto.nego=alpn
# Port for SPDY connections
spdy.port=8443
# SPDY idle timeout in milliseconds
spdy.timeout=30000
# Initial Window Size for SPDY
#spdy.initialWindowSize=65536
