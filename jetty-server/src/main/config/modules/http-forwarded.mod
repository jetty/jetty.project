# DO NOT EDIT - See: https://www.eclipse.org/jetty/documentation/current/startup-modules.html

[description]
Adds a forwarded request customizer to the HTTP Connector
to process forwarded-for style headers from a proxy.

[tags]
connector

[depend]
http

[xml]
etc/jetty-http-forwarded.xml

[ini-template]
### ForwardedRequestCustomizer Configuration

## If true, only the RFC7239 Forwarded header is accepted
# jetty.httpConfig.forwardedOnly=false

## if true, the proxy address obtained from X-Forwarded-Server or RFC7239 is used as the request authority.
# jetty.httpConfig.forwardedProxyAsAuthority=false

## if true, the X-Forwarded-Port header applies to the authority, else it applies to the remote client address
# jetty.httpConfig.forwardedPortAsAuthority=true

# jetty.httpConfig.forwardedHeader=Forwarded
# jetty.httpConfig.forwardedHostHeader=X-Forwarded-Host
# jetty.httpConfig.forwardedServerHeader=X-Forwarded-Server
# jetty.httpConfig.forwardedProtoHeader=X-Forwarded-Proto
# jetty.httpConfig.forwardedForHeader=X-Forwarded-For
# jetty.httpConfig.forwardedPortHeader=X-Forwarded-Port
# jetty.httpConfig.forwardedHttpsHeader=X-Proxied-Https
# jetty.httpConfig.forwardedSslSessionIdHeader=Proxy-ssl-id
# jetty.httpConfig.forwardedCipherSuiteHeader=Proxy-auth-cert

