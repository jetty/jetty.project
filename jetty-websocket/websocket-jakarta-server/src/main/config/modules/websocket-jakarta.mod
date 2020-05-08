DO NOT EDIT - See: https://www.eclipse.org/jetty/documentation/current/startup-modules.html

[description]
Enable jakarta.websocket for deployed web applications.

[tags]
websocket

[depend]
client
annotations

[lib]
lib/websocket/websocket-core-${jetty.version}.jar
lib/websocket/websocket-util-${jetty.version}.jar
lib/websocket/websocket-util-server-${jetty.version}.jar
lib/websocket/jetty-jakarta-websocket-api-1.1.2.jar
lib/websocket/websocket-jakarta-client-${jetty.version}.jar
lib/websocket/websocket-jakarta-common-${jetty.version}.jar
lib/websocket/websocket-jakarta-server-${jetty.version}.jar

[jpms]
# The implementation needs to access method handles in
# classes that are in the web application classloader.
add-reads: org.eclipse.jetty.websocket.jakarta.common=ALL-UNNAMED
