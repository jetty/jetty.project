# DO NOT EDIT - See: https://www.eclipse.org/jetty/documentation/current/startup-modules.html

[description]
Enables a Unix Domain Socket Connector.
The connector can receive requests from a local proxy and/or SSL offloader (eg haproxy) in either
HTTP or TCP mode.  Unix Domain Sockets are more efficient than localhost TCP/IP connections
as they reduce data copies, avoid needless fragmentation and have better dispatch behaviours.
When enabled with corresponding support modules, the connector can 
accept HTTP, HTTPS or HTTP2C traffic.

[deprecated]
Module 'unixsocket' is deprecated for removal.
Use 'unixdomain-http' instead (requires Java 16 or later).

[tags]
connector
deprecated

[depend]
server

[xml]
etc/jetty-unixsocket.xml

[lib]
lib/jetty-unixsocket-common-${jetty.version}.jar
lib/jetty-unixsocket-server-${jetty.version}.jar
lib/jnr/*.jar


