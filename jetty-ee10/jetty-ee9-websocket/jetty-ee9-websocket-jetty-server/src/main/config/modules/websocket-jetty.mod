[description]
Enable the Jetty WebSocket API support for deployed web applications.

[tags]
websocket

[depend]
annotations

[lib]
lib/websocket/websocket-core-common-${jetty.version}.jar
lib/websocket/websocket-core-server-${jetty.version}.jar
lib/websocket/websocket-servlet-${jetty.version}.jar
lib/websocket/websocket-jetty-api-${jetty.version}.jar
lib/websocket/websocket-jetty-common-${jetty.version}.jar
lib/websocket/websocket-jetty-server-${jetty.version}.jar

[jpms]
# The implementation needs to access method handles in
# classes that are in the web application classloader.
add-reads: org.eclipse.jetty.websocket.jetty.common=ALL-UNNAMED
