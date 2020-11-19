# DO NOT EDIT - See: https://www.eclipse.org/jetty/documentation/current/startup-modules.html

[description]
Enable javax.websocket for deployed web applications.

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
lib/websocket/jetty-javax-websocket-api-1.1.2.jar
lib/websocket/websocket-javax-client-${jetty.version}.jar
lib/websocket/websocket-javax-common-${jetty.version}.jar
lib/websocket/websocket-javax-server-${jetty.version}.jar

[jpms]
# The implementation needs to access method handles in
# classes that are in the web application classloader.
add-reads: org.eclipse.jetty.websocket.javax.common=ALL-UNNAMED
