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
## Max number of concurrent streams per connection
# jetty.http2.maxConcurrentStreams=1024

## Initial stream send (server to client) window
# jetty.http2.initialStreamSendWindow=65535
