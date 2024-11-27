[description]
Enables a TLS (SSL) connector to support secure protocols.
Secure HTTP/1.1 is provided by enabling the "https" module.
Secure HTTP/2 is provided by enabling the "http2" module.

[tags]
connector
ssl
internal

[depend]
ssl-context
server

[xml]
etc/jetty-ssl.xml

[ini-template]
# tag::documentation[]
### TLS (SSL) Connector Configuration

## The host/address to bind the connector to.
# jetty.ssl.host=0.0.0.0

## The port the connector listens on.
# jetty.ssl.port=8443

## The connector idle timeout, in milliseconds.
# jetty.ssl.idleTimeout=30000

## The number of acceptors (-1 picks a default value based on number of cores).
# jetty.ssl.acceptors=1

## The number of selectors (-1 picks a default value based on number of cores).
# jetty.ssl.selectors=-1

## The ServerSocketChannel accept queue backlog (0 picks the platform default).
# jetty.ssl.acceptQueueSize=0

## The thread priority delta to give to acceptor threads.
# jetty.ssl.acceptorPriorityDelta=0

## Whether to enable the SO_REUSEADDR socket option.
# jetty.ssl.reuseAddress=true

## Whether to enable the SO_REUSEPORT socket option.
# jetty.ssl.reusePort=false

## Whether to enable the TCP_NODELAY socket option on accepted sockets.
# jetty.ssl.acceptedTcpNoDelay=true

## The SO_RCVBUF socket option to set on accepted sockets.
## A value of -1 indicates that the platform default is used.
# jetty.ssl.acceptedReceiveBufferSize=-1

## The SO_SNDBUF socket option to set on accepted sockets.
## A value of -1 indicates that the platform default is used.
# jetty.ssl.acceptedSendBufferSize=-1

## Whether client SNI data is required for all secure connections.
## When SNI is required, clients that do not send SNI data are rejected with an HTTP 400 response.
# jetty.ssl.sniRequired=false

## Whether client SNI data is checked to match CN and SAN in server certificates.
## When SNI is checked, if the match fails the connection is rejected with an HTTP 400 response.
# jetty.ssl.sniHostCheck=true

## The max age, in seconds, for the Strict-Transport-Security response header.
# jetty.ssl.stsMaxAgeSeconds=31536000

## Whether to include the subdomain property in any Strict-Transport-Security header.
# jetty.ssl.stsIncludeSubdomains=true
# end::documentation[]
