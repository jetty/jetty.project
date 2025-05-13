[description]
Enables experimental support for the HTTP/3 protocol using the Quiche library.

[tags]
connector
http3
http
quic
quiche
experimental

[depends]
http2
quic-quiche-server
work

[provides]
http3-impl|default

[lib]
lib/http3/jetty-http3-common-${jetty.version}.jar
lib/http3/jetty-http3-qpack-${jetty.version}.jar
lib/http3/jetty-http3-server-${jetty.version}.jar

[xml]
etc/jetty-http3-quiche.xml

[ini-template]
# tag::documentation[]
## The host/address to bind the connector to.
# jetty.quic.host=0.0.0.0

## The port the connector listens on.
# jetty.quic.port=8444

## The connector idle timeout, in milliseconds.
# jetty.quic.idleTimeout=30000

## Specifies the maximum number of requests per session.
# jetty.quic.bidirectionalMaxStreams=131072

## Specifies the session max data (client to server) in bytes.
# jetty.quic.sessionMaxData=25165824

## Specifies the stream max data (client to server) in bytes.
# jetty.quic.remoteBidirectionalStreamMaxData=16777216

## Specifies the stream idle timeout, in milliseconds.
# jetty.http3.streamIdleTimeout=30000
# end::documentation[]
