[description]
Enable jakarta.websocket APIs for deployed web applications.

[tags]
websocket

[environment]
ee9

[depend]
client
ee9-annotations

[lib]
lib/ee9-websocket/jetty-websocket-core-common-${jetty.version}.jar
lib/ee9-websocket/jetty-websocket-core-client-${jetty.version}.jar
lib/ee9-websocket/jetty-websocket-core-server-${jetty.version}.jar
lib/ee9-websocket/jetty-ee9-websocket-servlet-${jetty.version}.jar
lib/ee9-websocket/jetty-jakarta-websocket-api-@jakarta.websocket.api.version@.jar
lib/ee9-websocket/jetty-ee9-websocket-jakarta-client-${jetty.version}.jar
lib/ee9-websocket/jetty-ee9-websocket-jakarta-common-${jetty.version}.jar
lib/ee9-websocket/jetty-ee9-websocket-jakarta-server-${jetty.version}.jar

[jpms]
# The implementation needs to access method handles in
# classes that are in the web application classloader.
add-reads: org.eclipse.jetty.websocket.jakarta.common=ALL-UNNAMED
