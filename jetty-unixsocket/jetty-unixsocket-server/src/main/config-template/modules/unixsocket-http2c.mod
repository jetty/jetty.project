# DO NOT EDIT - See: https://www.eclipse.org/jetty/documentation/current/startup-modules.html

[description]
Adds an HTTP2C connetion factory to the Unix Domain Socket Connector.
It can be used when either the proxy forwards direct
HTTP/2C (unecrypted) or decrypted HTTP/2 traffic.

[deprecated]
Module 'unixsocket-http2c' is deprecated for removal.
Use 'unixdomain-http' instead (requires Java 16 or later).

[tags]
connector
http2
deprecated

[depend]
unixsocket-http

[lib]
lib/http2/*.jar

[xml]
etc/jetty-unixsocket-http2c.xml

[ini-template]
## Max number of concurrent streams per connection
# jetty.http2.maxConcurrentStreams=128

## Initial stream receive window (client to server)
# jetty.http2.initialStreamRecvWindow=524288
