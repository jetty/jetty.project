DO NOT EDIT - See: https://www.eclipse.org/jetty/documentation/current/startup-modules.html

[description]
Enables the proxy protocol on the Unix Domain Socket Connector 
http://www.haproxy.org/download/1.5/doc/proxy-protocol.txt
This allows information about the proxied connection to be 
efficiently forwarded as the connection is accepted.
Both V1 and V2 versions of the protocol are supported and any
SSL properties may be interpreted by the unixsocket-secure 
module to indicate secure HTTPS traffic. Typically this
is an alternate to the forwarded module.

[tags]
connector

[depend]
unixsocket

[xml]
etc/jetty-unixsocket-proxy-protocol.xml
