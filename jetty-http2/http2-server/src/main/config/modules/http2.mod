#
# HTTP2 Support Module
#

[depend]
ssl
protonego

[lib]
lib/http2/*.jar

[xml]
etc/jetty-ssl.xml
etc/jetty-http2.xml

[ini-template]
## HTTP2 Configuration

# Port for HTTP2 connections
http2.port=8443

# HTTP2 idle timeout in milliseconds
http2.timeout=30000
