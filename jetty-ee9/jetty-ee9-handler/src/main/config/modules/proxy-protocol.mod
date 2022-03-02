[description]
Enables PROXY Protocol (https://www.haproxy.org/download/2.1/doc/proxy-protocol.txt)
support on the clear-text connector.
Both versions V1 and V2 of the PROXY protocol are supported.
The PROXY protocol allows a proxy server to send transport
details of the proxied connection to the Jetty server, so that
applications can transparently obtain transport information
of remote clients as if there was no proxy server.

[depend]
http

[xml]
etc/jetty-proxy-protocol.xml
