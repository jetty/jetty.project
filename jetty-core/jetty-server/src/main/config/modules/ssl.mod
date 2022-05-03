[description]
Enables a TLS (SSL) connector to support secure protocols.
Secure HTTP/1.1 is provided by enabling the "https" module and secure HTTP/2 is provided by enabling the "http2" module.

[tags]
connector
ssl
internal

[depend]
server

[xml]
etc/jetty-ssl.xml
etc/jetty-ssl-context.xml

[ini-template]
# tag::documentation-connector[]
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
# end::documentation-connector[]

# tag::documentation-ssl-context[]
### SslContextFactory Configuration
## Note that OBF passwords are not secure, just protected from casual observation.

## Whether client SNI data is required for all secure connections.
## When SNI is required, clients that do not send SNI data are rejected with a TLS handshake error.
# jetty.sslContext.sniRequired=false

## The Endpoint Identification Algorithm.
## Same as javax.net.ssl.SSLParameters#setEndpointIdentificationAlgorithm(String).
# jetty.sslContext.endpointIdentificationAlgorithm=

## The JSSE Provider.
# jetty.sslContext.provider=

## The KeyStore file path (relative to $JETTY_BASE).
# jetty.sslContext.keyStorePath=etc/keystore.p12
## The KeyStore absolute file path.
# jetty.sslContext.keyStoreAbsolutePath=${jetty.base}/etc/keystore.p12

## The TrustStore file path (relative to $JETTY_BASE).
# jetty.sslContext.trustStorePath=etc/keystore.p12
## The TrustStore absolute file path.
# jetty.sslContext.trustStoreAbsolutePath=${jetty.base}/etc/keystore.p12

## The KeyStore password.
# jetty.sslContext.keyStorePassword=

## The Keystore type.
# jetty.sslContext.keyStoreType=PKCS12

## The KeyStore provider.
# jetty.sslContext.keyStoreProvider=

## The KeyManager password.
# jetty.sslContext.keyManagerPassword=

## The TrustStore password.
# jetty.sslContext.trustStorePassword=

## The TrustStore type.
# jetty.sslContext.trustStoreType=PKCS12

## The TrustStore provider.
# jetty.sslContext.trustStoreProvider=

## Whether client certificate authentication is required.
# jetty.sslContext.needClientAuth=false

## Whether client certificate authentication is desired, but not required.
# jetty.sslContext.wantClientAuth=false

## Whether cipher order is significant.
# jetty.sslContext.useCipherSuitesOrder=true

## The SSLSession cache size.
# jetty.sslContext.sslSessionCacheSize=-1

## The SSLSession cache timeout (in seconds).
# jetty.sslContext.sslSessionTimeout=-1

## Whether TLS renegotiation is allowed.
# jetty.sslContext.renegotiationAllowed=true

## The max number of TLS renegotiations per connection.
# jetty.sslContext.renegotiationLimit=5
# end::documentation-ssl-context[]
