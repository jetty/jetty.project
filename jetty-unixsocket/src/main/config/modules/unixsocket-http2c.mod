[description]
Adds a HTTP2C connetion factory to the Unix Domain Socket Connector
It can be used when either the proxy forwards direct
HTTP/2C (unecrypted) or decrypted HTTP/2 traffic.

[tags]
connector
http2

[depend]
unixsocket-http

[lib]
lib/http2/*.jar

[xml]
etc/jetty-unixsocket-http2c.xml

[ini-template]
## Max number of concurrent streams per connection
# jetty.http2.maxConcurrentStreams=1024

## Initial stream receive window (client to server)
# jetty.http2.initialStreamRecvWindow=65535
