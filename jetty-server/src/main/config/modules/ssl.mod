# DO NOT EDIT - See: https://www.eclipse.org/jetty/documentation/current/startup-modules.html

[description]
Enables a TLS(SSL) Connector on the server.
This may be used for HTTPS and/or HTTP2 by enabling
the associated support modules.

[tags]
connector
ssl

[depend]
server

[xml]
etc/jetty-ssl.xml
etc/jetty-ssl-context.xml

[files]
basehome:modules/ssl/keystore|etc/keystore

[ini-template]
### TLS(SSL) Connector Configuration

## Connector host/address to bind to
# jetty.ssl.host=0.0.0.0

## Connector port to listen on
# jetty.ssl.port=8443

## Connector idle timeout in milliseconds
# jetty.ssl.idleTimeout=30000

## Number of acceptors (-1 picks default based on number of cores)
# jetty.ssl.acceptors=-1

## Number of selectors (-1 picks default based on number of cores)
# jetty.ssl.selectors=-1

## ServerSocketChannel backlog (0 picks platform default)
# jetty.ssl.acceptQueueSize=0

## Thread priority delta to give to acceptor threads
# jetty.ssl.acceptorPriorityDelta=0

## The requested maximum length of the queue of incoming connections.
# jetty.ssl.acceptQueueSize=0

## Enable/disable the SO_REUSEADDR socket option.
# jetty.ssl.reuseAddress=true

## Enable/disable TCP_NODELAY on accepted sockets.
# jetty.ssl.acceptedTcpNoDelay=true

## The SO_RCVBUF option to set on accepted sockets. A value of -1 indicates that it is left to its default value.
# jetty.ssl.acceptedReceiveBufferSize=-1

## The SO_SNDBUF option to set on accepted sockets. A value of -1 indicates that it is left to its default value.
# jetty.ssl.acceptedSendBufferSize=-1

## Connect Timeout in milliseconds
# jetty.ssl.connectTimeout=15000

## Whether SNI is required for all secure connections. Rejections are in TLS handshakes.
# jetty.sslContext.sniRequired=false

## Whether SNI is required for all secure connections. Rejections are in HTTP 400 response.
# jetty.ssl.sniRequired=false

## Whether request host names are checked to match any SNI names
# jetty.ssl.sniHostCheck=true

## max age in seconds for a Strict-Transport-Security response header (default -1)
# jetty.ssl.stsMaxAgeSeconds=31536000

## include subdomain property in any Strict-Transport-Security header (default false)
# jetty.ssl.stsIncludeSubdomains=true

### SslContextFactory Configuration
## Note that OBF passwords are not secure, just protected from casual observation
## See http://www.eclipse.org/jetty/documentation/current/configuring-security-secure-passwords.html

## The Endpoint Identification Algorithm
## Same as javax.net.ssl.SSLParameters#setEndpointIdentificationAlgorithm(String)
#jetty.sslContext.endpointIdentificationAlgorithm=

## SSL JSSE Provider
# jetty.sslContext.provider=

## Keystore file path (relative to $jetty.base)
# jetty.sslContext.keyStorePath=etc/keystore

## Truststore file path (relative to $jetty.base)
# jetty.sslContext.trustStorePath=etc/keystore

## Keystore password
# jetty.sslContext.keyStorePassword=OBF:1vny1zlo1x8e1vnw1vn61x8g1zlu1vn4

## Keystore type and provider
# jetty.sslContext.keyStoreType=JKS
# jetty.sslContext.keyStoreProvider=

## KeyManager password
# jetty.sslContext.keyManagerPassword=OBF:1u2u1wml1z7s1z7a1wnl1u2g

## Truststore password
# jetty.sslContext.trustStorePassword=OBF:1vny1zlo1x8e1vnw1vn61x8g1zlu1vn4

## Truststore type and provider
# jetty.sslContext.trustStoreType=JKS
# jetty.sslContext.trustStoreProvider=

## whether client certificate authentication is required
# jetty.sslContext.needClientAuth=false

## Whether client certificate authentication is desired
# jetty.sslContext.wantClientAuth=false

## Whether cipher order is significant (since java 8 only)
# jetty.sslContext.useCipherSuitesOrder=true

## To configure Includes / Excludes for Cipher Suites or Protocols see tweak-ssl.xml example at
## https://www.eclipse.org/jetty/documentation/current/configuring-ssl.html#configuring-sslcontextfactory-cipherSuites

## Set the size of the SslSession cache
# jetty.sslContext.sslSessionCacheSize=-1

## Set the timeout (in seconds) of the SslSession cache timeout
# jetty.sslContext.sslSessionTimeout=-1

## Allow SSL renegotiation
# jetty.sslContext.renegotiationAllowed=true
# jetty.sslContext.renegotiationLimit=5
