# DO NOT EDIT - See: https://www.eclipse.org/jetty/documentation/current/startup-modules.html

[description]
Adds a forwarded request customizer for the  Unix Domain Socket connector.
For use when behind a proxy operating in HTTP mode that adds forwarded-for style HTTP headers. 
Typically this is an alternate to the Proxy Protocol used mostly for TCP mode.

[deprecated]
Module 'unixsocket-forwarded' is deprecated for removal.
Use 'unixdomain-http' instead (requires Java 16 or later).

[tags]
connector
deprecated

[depend]
unixsocket-http

[xml]
etc/jetty-unixsocket-forwarded.xml

[ini-template]
### ForwardedRequestCustomizer Configuration
# jetty.unixSocketHttpConfig.forwardedHostHeader=X-Forwarded-Host
# jetty.unixSocketHttpConfig.forwardedServerHeader=X-Forwarded-Server
# jetty.unixSocketHttpConfig.forwardedProtoHeader=X-Forwarded-Proto
# jetty.unixSocketHttpConfig.forwardedForHeader=X-Forwarded-For
# jetty.unixSocketHttpConfig.forwardedSslSessionIdHeader=
# jetty.unixSocketHttpConfig.forwardedCipherSuiteHeader=




