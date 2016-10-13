[description]
Enables HTTP2 protocol support on the TLS(SSL) Connector,
using the ALPN extension to select which protocol to use.

[tags]
connector
http2
http
ssl

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

## Initial stream receive window (client to server)
# jetty.http2.initialStreamRecvWindow=65535
