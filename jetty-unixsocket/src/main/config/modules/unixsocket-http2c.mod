[description]
Adds a HTTP2C connetion factory to the Unix Domain Socket Connector
It can be used when either the proxy forwards direct
HTTP/2C (unecrypted) or decrypted HTTP/2 traffic.

[depend]
unixsocket-http

[lib]
lib/http2/*.jar

[xml]
etc/jetty-unixsocket-http2c.xml

[ini-template]
## Max number of concurrent streams per connection
# jetty.http2.maxConcurrentStreams=1024

## Initial stream send (server to client) window
# jetty.http2.initialStreamSendWindow=65535

