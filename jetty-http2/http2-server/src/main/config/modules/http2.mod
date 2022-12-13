[description]
Enables the support for the secure HTTP/2 protocol.

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
# tag::documentation[]
## Specifies the maximum number of concurrent requests per session.
# jetty.http2.maxConcurrentStreams=128

## Specifies the initial stream receive window (client to server) in bytes.
# jetty.http2.initialStreamRecvWindow=524288

## Specifies the initial session receive window (client to server) in bytes.
# jetty.http2.initialSessionRecvWindow=1048576

## Specifies the maximum number of keys in all SETTINGS frames received by a session.
# jetty.http2.maxSettingsKeys=64

## Specifies the maximum number of bad frames and pings per second,
## after which a session is closed to avoid denial of service attacks.
# jetty.http2.rateControl.maxEventsPerSecond=50
# end::documentation[]
