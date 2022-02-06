# DO NOT EDIT - See: https://www.eclipse.org/jetty/documentation/current/startup-modules.html

[description]
Enable a secure request customizer on the HTTP Configuration.
Used by the Unix Domain Socket Connector.
This looks for a secure scheme transported either by the
unixsocket-forwarded, unixsocket-proxy-protocol or in a
HTTP2 request.

[deprecated]
Module 'unixsocket-secure' is deprecated for removal.
Use 'unixdomain-http' instead (requires Java 16 or later).

[tags]
connector
deprecated

[depend]
unixsocket-http

[xml]
etc/jetty-unixsocket-secure.xml

[ini-template]
### SecureRequestCustomizer Configuration


