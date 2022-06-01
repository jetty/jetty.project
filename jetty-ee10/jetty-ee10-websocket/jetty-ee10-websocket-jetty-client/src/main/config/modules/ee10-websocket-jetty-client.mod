# DO NOT EDIT - See: https://www.eclipse.org/jetty/documentation/current/startup-modules.html

[description]
Expose the Jetty WebSocket Client classes to deployed web applications.

[environment]
ee10

[tags]
websocket

[depend]
client
ee10-annotations

[lib]
lib/jetty-websocket-core-common-${jetty.version}.jar
lib/jetty-websocket-core-client-${jetty.version}.jar
lib/websocket/jetty-ee10-websocket-jetty-api-${jetty.version}.jar
lib/websocket/jetty-ee10-websocket-jetty-common-${jetty.version}.jar
lib/websocket/jetty-ee10-websocket-jetty-client-${jetty.version}.jar

[jpms]
# The implementation needs to access method handles in
# classes that are in the web application classloader.
add-reads: org.eclipse.jetty.ee10.websocket.jetty.common=ALL-UNNAMED
