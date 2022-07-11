# DO NOT EDIT - See: https://www.eclipse.org/jetty/documentation/current/startup-modules.html

[description]
Expose the Jetty WebSocket Client classes to deployed web applications.

[tags]
websocket

[environment]
ee9

[depend]
client
ee9-annotations

[lib]
lib/ee9-websocket/websocket-core-common-${jetty.version}.jar
lib/ee9-websocket/websocket-core-client-${jetty.version}.jar
lib/ee9-websocket/jetty-ee9-websocket-jetty-api-${jetty.version}.jar
lib/ee9-websocket/jetty-ee9-websocket-jetty-common-${jetty.version}.jar
lib/ee9-websocket/jetty-ee9-websocket-jetty-client-${jetty.version}.jar

[jpms]
# The implementation needs to access method handles in
# classes that are in the web application classloader.
add-reads: org.eclipse.jetty.websocket.jetty.common=ALL-UNNAMED
