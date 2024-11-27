[description]
Enables the TLS (SSL) configuration to support secure protocols.

[tags]
connector
ssl
internal

[depend]
server

[xml]
etc/jetty-ssl-context.xml

[ini-template]
# tag::documentation[]
### SslContextFactory Configuration
### Note that OBF passwords are not secure, just protected from casual observation.

## The JSSE Provider.
# jetty.sslContext.provider=

## The KeyStore file path, either an absolute path or a relative path to $JETTY_BASE.
# jetty.sslContext.keyStorePath=etc/keystore.p12

## The KeyStore password.
# jetty.sslContext.keyStorePassword=

## The Keystore type.
# jetty.sslContext.keyStoreType=PKCS12

## The KeyStore provider.
# jetty.sslContext.keyStoreProvider=

## The KeyManager password.
# jetty.sslContext.keyManagerPassword=

## The TrustStore file path, either an absolute path or a relative path to $JETTY_BASE.
# jetty.sslContext.trustStorePath=etc/keystore.p12

## The TrustStore password.
# jetty.sslContext.trustStorePassword=

## The TrustStore type.
# jetty.sslContext.trustStoreType=PKCS12

## The TrustStore provider.
# jetty.sslContext.trustStoreProvider=

## The Endpoint Identification Algorithm.
## Same as javax.net.ssl.SSLParameters#setEndpointIdentificationAlgorithm(String).
# jetty.sslContext.endpointIdentificationAlgorithm=

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

## Whether client SNI data is required for all secure connections.
## When SNI is required, clients that do not send SNI data are rejected with a TLS handshake error.
# jetty.sslContext.sniRequired=false

## The specific TLS protocol to use.
# jetty.sslContext.protocol=

## A comma-separated list of TLS protocols to include.
# jetty.sslContext.includeProtocols=

## A comma-separated list of TLS protocols to exclude.
# jetty.sslContext.excludeProtocols=SSL,SSLv2,SSLv2Hello,SSLv3

## A comma-separated list of cipher suites to include.
# jetty.sslContext.includeCipherSuites=

## A comma-separated list of cipher suites regular expression patterns to exclude.
# jetty.sslContext.excludeCipherSuites=^.*_(MD5|SHA|SHA1)$,^SSL_.*$,^.*_NULL_.*$,^.*_anon_.*$

## The alias to use when the KeyStore contains multiple entries.
# jetty.sslContext.alias=

## Whether to validate the certificates in the KeyStore at startup.
# jetty.sslContext.validateCertificates=false

## Whether to validate peer certificates received during the TLS handshake.
# jetty.sslContext.validatePeerCertificates=false

## The secure random algorithm to use to initialize the SSLContext.
# jetty.sslContext.secureRandomAlgorithm=

## The KeyManagerFactory algorithm.
# jetty.sslContext.keyManagerFactoryAlgorithm=

# The TrustManagerFactory algorithm.
# jetty.sslContext.trustManagerFactoryAlgorithm=

## Whether TLS session caching should be used.
# jetty.sslContext.sessionCachingEnabled=true
# end::documentation[]
