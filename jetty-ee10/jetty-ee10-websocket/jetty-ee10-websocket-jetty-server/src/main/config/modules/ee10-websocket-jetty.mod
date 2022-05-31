[description]
Enable the Jetty WebSocket API support for deployed web applications.

[tags]
websocket

[environment]
ee10

[depend]
ee10-annotations

[lib]
lib/websocket/jetty-ee10-websocket-core-common-${jetty.version}.jar
lib/websocket/jetty-ee10-websocket-core-server-${jetty.version}.jar
lib/websocket/jetty-ee10-websocket-servlet-${jetty.version}.jar
lib/websocket/jetty-ee10-websocket-jetty-api-${jetty.version}.jar
lib/websocket/jetty-ee10-websocket-jetty-common-${jetty.version}.jar
lib/websocket/jetty-ee10-websocket-jetty-server-${jetty.version}.jar

[jpms]
# The implementation needs to access method handles in
# classes that are in the web application classloader.
add-reads: org.eclipse.jetty.ee10.websocket.jetty.common=ALL-UNNAMED
