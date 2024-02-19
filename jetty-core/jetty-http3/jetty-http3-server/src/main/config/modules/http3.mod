[description]
Enables experimental support for the HTTP/3 protocol.

[tags]
connector
http3
http
quic
experimental

[depend]
http2
jna
quiche
work

[lib]
lib/http3/jetty-http3-common-${jetty.version}.jar
lib/http3/jetty-http3-qpack-${jetty.version}.jar
lib/http3/jetty-http3-server-${jetty.version}.jar
lib/http3/jetty-quic-common-${jetty.version}.jar
lib/http3/jetty-quic-quiche-common-${jetty.version}.jar
lib/http3/jetty-quic-quiche-foreign-incubator-${jetty.version}.jar
lib/http3/jetty-quic-quiche-jna-${jetty.version}.jar
lib/http3/jetty-quic-server-${jetty.version}.jar

[xml]
etc/jetty-http3.xml

[ini-template]
# tag::documentation[]
## The host/address to bind the connector to.
# jetty.quic.host=0.0.0.0

## The port the connector listens on.
# jetty.quic.port=8444

## The connector idle timeout, in milliseconds.
# jetty.quic.idleTimeout=30000

## Specifies the maximum number of concurrent requests per session.
# jetty.quic.maxBidirectionalRemoteStreams=128

## Specifies the session receive window (client to server) in bytes.
# jetty.quic.sessionRecvWindow=4194304

## Specifies the stream receive window (client to server) in bytes.
# jetty.quic.bidirectionalStreamRecvWindow=2097152

## Specifies the stream idle timeout, in milliseconds.
# jetty.http3.streamIdleTimeout=30000
# end::documentation[]
