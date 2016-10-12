[description]
Enables a Unix Domain Socket Connector that can receive
requests from a local proxy and/or SSL offloader (eg haproxy) in either
HTTP or TCP mode.  Unix Domain Sockets are more efficient than 
localhost TCP/IP connections  as they reduce data copies, avoid 
needless fragmentation and have better dispatch behaviours. 
When enabled with corresponding support modules, the connector can 
accept HTTP, HTTPS or HTTP2C traffic.

[tags]
connector

[depend]
server

[xml]
etc/jetty-unixsocket.xml

[files]
maven://com.github.jnr/jnr-unixsocket/0.8|lib/jnr/jnr-unixsocket-0.8.jar
maven://com.github.jnr/jnr-ffi/2.0.3|lib/jnr/jnr-ffi-2.0.3.jar
maven://com.github.jnr/jffi/1.2.9|lib/jnr/jffi-1.2.9.jar
maven://com.github.jnr/jffi/1.2.9/jar/native|lib/jnr/jffi-1.2.9-native.jar
maven://org.ow2.asm/asm/5.0.1|lib/jnr/asm-5.0.1.jar
maven://org.ow2.asm/asm-commons/5.0.1|lib/jnr/asm-commons-5.0.1.jar
maven://org.ow2.asm/asm-analysis/5.0.3|lib/jnr/asm-analysis-5.0.3.jar
maven://org.ow2.asm/asm-tree/5.0.3|lib/jnr/asm-tree-5.0.3.jar
maven://org.ow2.asm/asm-util/5.0.3|lib/jnr/asm-util-5.0.3.jar
maven://com.github.jnr/jnr-x86asm/1.0.2|lib/jnr/jnr-x86asm-1.0.2.jar
maven://com.github.jnr/jnr-constants/0.8.7|lib/jnr/jnr-constants-0.8.7.jar
maven://com.github.jnr/jnr-enxio/0.9|lib/jnr/jnr-enxio-0.9.jar
maven://com.github.jnr/jnr-posix/3.0.12|lib/jnr/jnr-posix-3.0.12.jar

[lib]
lib/jetty-unixsocket-${jetty.version}.jar
lib/jnr/*.jar

[license]
Jetty UnixSockets is implmented using the Java Native Runtime, which is an 
open source project hosted on Github and released under the Apache 2.0 license.
https://github.com/jnr/jnr-unixsocket
http://www.apache.org/licenses/LICENSE-2.0.html

[ini-template]
### Unix SocketHTTP Connector Configuration

## Connector host/address to bind to
# jetty.unixsocket=/tmp/jetty.sock

## Connector idle timeout in milliseconds
# jetty.unixsocket.idleTimeout=30000

## Number of selectors (-1 picks default 1)
# jetty.unixsocket.selectors=-1

## ServerSocketChannel backlog (0 picks platform default)
# jetty.unixsocket.acceptorQueueSize=0
