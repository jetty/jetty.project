#
# SSL Keystore module
#

[depend]
server

[xml]
etc/jetty-ssl.xml

[files]
http://git.eclipse.org/c/jetty/org.eclipse.jetty.project.git/plain/jetty-server/src/main/config/etc/keystore|etc/keystore

[ini-template]
### TLS(SSL) Connector Configuration

## Connector host/address to bind to
# jetty.ssl.host=0.0.0.0

## Connector port to listen on
# jetty.ssl.port=443

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

### SslContextFactory Configuration

## Keystore file path (relative to $jetty.base)
# jetty.sslConfig.keyStorePath=etc/keystore

## Truststore file path (relative to $jetty.base)
# jetty.sslConfig.trustStorePath=etc/keystore

## Note that OBF passwords are not secure, just protected from casual observation
## See http://www.eclipse.org/jetty/documentation/current/configuring-security-secure-passwords.html

## Keystore password
# jetty.sslConfig.keyStorePassword=OBF:1vny1zlo1x8e1vnw1vn61x8g1zlu1vn4

## KeyManager password
# jetty.sslConfig.keyManagerPassword=OBF:1u2u1wml1z7s1z7a1wnl1u2g

## Truststore password
# jetty.sslConfig.trustStorePassword=OBF:1vny1zlo1x8e1vnw1vn61x8g1zlu1vn4

## whether client certificate authentication is required
# jetty.sslConfig.needClientAuth=false

## Whether client certificate authentication is desired
# jetty.sslConfig.wantClientAuth=false
