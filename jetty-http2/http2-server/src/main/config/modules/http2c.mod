[description]
Enables the support for the clear-text HTTP/2 protocol.

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
# tag::documentation[]
## Specifies the maximum number of concurrent requests per session.
# jetty.http2c.maxConcurrentStreams=1024

  ## Specifies the initial stream receive window (client to server) in bytes.
# jetty.http2c.initialStreamRecvWindow=65535

## Specifies the initial session receive window (client to server) in bytes.
# jetty.http2.initialSessionRecvWindow=1048576

## Specifies the maximum number of keys in all SETTINGS frames received by a session.
# jetty.http2.maxSettingsKeys=64

## Specifies the maximum number of bad frames and pings per second,
## after which a session is closed to avoid denial of service attacks.
# jetty.http2.rateControl.maxEventsPerSecond=20
# end::documentation[]
