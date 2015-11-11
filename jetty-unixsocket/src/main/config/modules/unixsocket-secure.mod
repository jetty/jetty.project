# 
# Unix Socket Secure Module
# 
# This module adds a secure request customizer to the 
# Unix Socket Connector.    This looks for SSL properties
# that may be transported by the unixsocket-proxy-protocol
# module, to indicate a HTTPS request.  This is not required
# for HTTP/2 (which carries scheme) or if the unixsocket-forwarded
# module is used with a forwarded scheme
#

[depend]
unixsocket-http

[xml]
etc/jetty-unixsocket-secure.xml

[ini-template]
### SecureRequestCustomizer Configuration


