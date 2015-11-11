#
# Unix Socket HTTP2C Module
#
# This module adds a HTTP2C connetion factory to the Unix Socket
# Connector.  It can be used when either the proxy received direct
# HTTP/2C (unecrypted) traffic; or the proxy is decrypting HTTP/2
# traffic before forwarding it. 
# 

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

