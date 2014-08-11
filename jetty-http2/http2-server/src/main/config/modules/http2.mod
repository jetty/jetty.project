#
# HTTP2 Support Module
#

[depend]
ssl-protonego

[lib]
lib/http2/*.jar

[xml]
etc/jetty-http2.xml

[ini-template]
## HTTP2 Configuration

# Advertised protocols
protonego.protocols=h2-14,http/1.1
protonego.defaultProtocol=http/1.1

# http2.maxConcurrentStreams=1024
