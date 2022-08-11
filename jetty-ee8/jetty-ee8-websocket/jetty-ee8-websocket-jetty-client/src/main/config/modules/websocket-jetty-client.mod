# DO NOT EDIT - See: https://www.eclipse.org/jetty/documentation/current/startup-modules.html

[description]
Expose the Jetty WebSocket Client classes to deployed web applications.

[tags]
websocket

[depend]
client
annotations

[lib]
lib/websocket/jetty-websocket-core-common-${jetty.version}.jar
lib/websocket/jetty-websocket-core-client-${jetty.version}.jar
lib/websocket/websocket-jetty-api-${jetty.version}.jar
lib/websocket/websocket-jetty-common-${jetty.version}.jar
lib/websocket/websocket-jetty-client-${jetty.version}.jar

[jpms]
# The implementation needs to access method handles in
# classes that are in the web application classloader.
add-reads: org.eclipse.jetty.websocket.jetty.common=ALL-UNNAMED
