#
# SSL Keystore module
#

[name]
ssl

[depend]
server

[xml]
etc/jetty-ssl.xml
etc/jetty-ssl-context.xml

[files]
http://git.eclipse.org/c/jetty/org.eclipse.jetty.project.git/plain/jetty-server/src/test/config/etc/keystore?id=${jetty.tag.version}|etc/keystore

[ini-template]
### TLS(SSL) Connector Configuration

## Connector host/address to bind to
# jetty.ssl.host=0.0.0.0

## Connector port to listen on
# jetty.ssl.port=8443

## Connector idle timeout in milliseconds
# jetty.ssl.idleTimeout=30000

## Connector socket linger time in seconds (-1 to disable)
# jetty.ssl.soLingerTime=-1

## Number of acceptors (-1 picks default based on number of cores)
# jetty.ssl.acceptors=-1

## Number of selectors (-1 picks default based on number of cores)
# jetty.ssl.selectors=-1

## ServerSocketChannel backlog (0 picks platform default)
# jetty.ssl.acceptorQueueSize=0

## Thread priority delta to give to acceptor threads
# jetty.ssl.acceptorPriorityDelta=0

## Whether request host names are checked to match any SNI names
# jetty.ssl.sniHostCheck=true

### SslContextFactory Configuration
## Note that OBF passwords are not secure, just protected from casual observation
## See http://www.eclipse.org/jetty/documentation/current/configuring-security-secure-passwords.html

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

