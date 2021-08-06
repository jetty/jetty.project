# DO NOT EDIT - See: https://www.eclipse.org/jetty/documentation/current/startup-modules.html

[description]
Enables the HTTP2C protocol on the HTTP Connector
The connector will accept both HTTP/1 and HTTP/2 connections.

[tags]
connector
http2
http

[depend]
http

[lib]
lib/http2/*.jar

[xml]
etc/jetty-http2c.xml

[ini-template]
## Max number of concurrent streams per connection
# jetty.http2c.maxConcurrentStreams=1024

## Initial stream receive window (client to server)
# jetty.http2c.initialStreamRecvWindow=524288

## Initial session receive window (client to server)
# jetty.http2c.initialSessionRecvWindow=1048576

## The max number of keys in all SETTINGS frames
# jetty.http2c.maxSettingsKeys=64

## Max number of bad frames and pings per second
# jetty.http2c.rateControl.maxEventsPerSecond=50
