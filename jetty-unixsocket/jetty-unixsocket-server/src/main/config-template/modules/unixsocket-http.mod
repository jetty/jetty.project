# DO NOT EDIT THIS FILE - Use start modules correctly - See: https://eclipse.dev/jetty/documentation/

[description]
Adds an HTTP protocol support to the Unix Domain Socket connector.
It should be used when a proxy is forwarding either HTTP or decrypted
HTTPS traffic to the connector and may be used with the 
unix-socket-http2c modules to upgrade to HTTP/2.

[deprecated]
Module 'unixsocket-http' is deprecated for removal.
Use 'unixdomain-http' instead (requires Java 16 or later).

[tags]
connector
http
deprecated

[depend]
unixsocket

[xml]
etc/jetty-unixsocket-http.xml



