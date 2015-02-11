#
# HTTP2 Support Module
#

[depend]
ssl
alpn

[lib]
lib/http2/*.jar

[xml]
etc/jetty-http2.xml

[ini-template]
## HTTP2 Configuration

# http2.maxConcurrentStreams=1024
