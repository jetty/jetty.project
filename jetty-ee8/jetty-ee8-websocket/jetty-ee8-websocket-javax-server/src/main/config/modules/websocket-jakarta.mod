[description]
Enable jakarta.websocket APIs for deployed web applications.

[tags]
websocket

[depend]
client
annotations

[lib]
lib/websocket/websocket-core-common-${jetty.version}.jar
lib/websocket/websocket-core-client-${jetty.version}.jar
lib/websocket/websocket-core-server-${jetty.version}.jar
lib/websocket/websocket-servlet-${jetty.version}.jar
lib/websocket/jetty-jakarta-websocket-api-2.0.0.jar
lib/websocket/websocket-jakarta-client-${jetty.version}.jar
lib/websocket/websocket-jakarta-common-${jetty.version}.jar
lib/websocket/websocket-jakarta-server-${jetty.version}.jar

[jpms]
# The implementation needs to access method handles in
# classes that are in the web application classloader.
add-reads: org.eclipse.jetty.websocket.jakarta.common=ALL-UNNAMED
