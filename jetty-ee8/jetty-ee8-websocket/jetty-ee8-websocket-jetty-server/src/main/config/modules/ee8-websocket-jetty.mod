# DO NOT EDIT - See: https://www.eclipse.org/jetty/documentation/current/startup-modules.html

[description]
Enable the Jetty WebSocket API support for deployed web applications.

[environment]
ee8

[tags]
websocket

[depend]
ee8-annotations

[lib]
lib/ee8-websocket/websocket-core-common-${jetty.version}.jar
lib/ee8-websocket/websocket-core-server-${jetty.version}.jar
lib/ee8-websocket/jetty-ee8-websocket-servlet-${jetty.version}.jar
lib/ee8-websocket/jetty-ee8-websocket-jetty-api-${jetty.version}.jar
lib/ee8-websocket/jetty-ee8-websocket-jetty-common-${jetty.version}.jar
lib/ee8-websocket/jetty-ee8-websocket-jetty-server-${jetty.version}.jar

[jpms]
# The implementation needs to access method handles in
# classes that are in the web application classloader.
add-reads: org.eclipse.jetty.websocket.jetty.common=ALL-UNNAMED
