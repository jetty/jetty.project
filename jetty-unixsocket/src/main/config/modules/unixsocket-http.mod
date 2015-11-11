#
# Unix Socket HTTP Module
#
# This module adds a HTTP connection factory to the Unix Socket connector.
# It should be used when the proxy is forwarding either HTTP or decrypted
# HTTPS traffic.
# 
# If the proxy is decrypting SSL/TLS, then either the unixsocket-forwarded 
# (for HTTP mode) or unixsocket-proxy-connection (for TCP mode) should be
# used to set the scheme and certificate information correctly.
#
#

[depend]
unixsocket

[xml]
etc/jetty-unixsocket-http.xml



