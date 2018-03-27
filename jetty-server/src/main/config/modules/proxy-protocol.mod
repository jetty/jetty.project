DO NOT EDIT - See: https://www.eclipse.org/jetty/documentation/current/startup-modules.html

[description]
Enables the Proxy Protocol on the HTTP Connector.
http://www.haproxy.org/download/1.5/doc/proxy-protocol.txt
This allows a proxy operating in TCP mode to 
transport details of the proxied connection to
the server.
Both V1 and V2 versions of the protocol are supported. 

[depend]
http

[xml]
etc/jetty-proxy-protocol.xml
