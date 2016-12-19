[description]
Enables the Proxy Protocol on the TLS(SSL) Connector.
http://www.haproxy.org/download/1.5/doc/proxy-protocol.txt
This allows a Proxy operating in TCP mode to transport
details of the proxied connection to the server.
Both V1 and V2 versions of the protocol are supported.

[tags]
connector
ssl

[depend]
ssl

[xml]
etc/jetty-proxy-protocol-ssl.xml
