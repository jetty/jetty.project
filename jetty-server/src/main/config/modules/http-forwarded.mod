DO NOT EDIT - See: https://www.eclipse.org/jetty/documentation/current/startup-modules.html

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

# jetty.httpConfig.forwardedOnly=false
# jetty.httpConfig.forwardedProxyAsAuthority=false
# jetty.httpConfig.forwardedHeader=Forwarded
# jetty.httpConfig.forwardedHostHeader=X-Forwarded-Host
# jetty.httpConfig.forwardedServerHeader=X-Forwarded-Server
# jetty.httpConfig.forwardedProtoHeader=X-Forwarded-Proto
# jetty.httpConfig.forwardedForHeader=X-Forwarded-For
# jetty.httpConfig.forwardedPortHeader=X-Forwarded-Port
# jetty.httpConfig.forwardedHttpsHeader=X-Proxied-Https
# jetty.httpConfig.forwardedSslSessionIdHeader=Proxy-ssl-id
# jetty.httpConfig.forwardedCipherSuiteHeader=Proxy-auth-cert

