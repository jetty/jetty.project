DO NOT EDIT - See: https://www.eclipse.org/jetty/documentation/current/startup-modules.html

[description]
Enable jetty websocket for deployed web applications.

[tags]
websocket

[depend]
client
annotations

[lib]
lib/websocket/websocket-core-${jetty.version}.jar
lib/websocket/websocket-servlet-${jetty.version}.jar
lib/websocket/websocket-jetty-*.jar

[jpms]
# The implementation needs to access method handles in
# classes that are in the web application classloader.
add-reads: org.eclipse.jetty.websocket.common=ALL-UNNAMED
