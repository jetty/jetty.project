#
# Unix Socket forwarded module
#
# This module adds the jetty-unixsocket-forwarded.xml to the configuration
# and is for use with the unixsocket-http module when the proxy is operating 
# in HTTP mode and adding forwarded-for style headers
#

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




