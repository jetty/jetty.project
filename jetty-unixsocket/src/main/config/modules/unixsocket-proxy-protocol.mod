#
# Unix Socket PROXY Protocol Module
#
# This module adds the proxy protocol connection factory to the 
# unixsocket connector:
#    http://www.haproxy.org/download/1.5/doc/proxy-protocol.txt
# Both V1 and V2 versions of the protocol are supported and any
# SSL properties transported can be interpreted by the 
# unixsocket-secure module to indicate secure HTTPS traffic.

[depend]
unixsocket

[xml]
etc/jetty-unixsocket-proxy-protocol.xml
