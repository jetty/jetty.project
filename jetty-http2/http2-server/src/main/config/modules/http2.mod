#
# HTTP2 Support Module
#

[depend]
ssl
protonego

[lib]
lib/http2/*.jar

[xml]
etc/protonego-${protonego}.xml
etc/jetty-http2.xml

[ini-template]
## HTTP2 Configuration

# http2.maxConcurrentStreams=1024
