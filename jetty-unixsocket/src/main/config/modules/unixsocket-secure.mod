[description]
Enable a secure request customizer on the HTTP Configuration
used by the Unix Domain Socket Connector.
This looks for a secure scheme transported either by the
unixsocket-forwarded, unixsocket-proxy-protocol or in a
HTTP2 request.

[tags]
connector

[depend]
unixsocket-http

[xml]
etc/jetty-unixsocket-secure.xml

[ini-template]
### SecureRequestCustomizer Configuration


