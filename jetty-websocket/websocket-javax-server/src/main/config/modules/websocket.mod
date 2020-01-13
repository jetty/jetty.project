DO NOT EDIT - See: https://www.eclipse.org/jetty/documentation/current/startup-modules.html

[description]
Enable websockets for deployed web applications

[depend]
# websocket client needs jetty-client
client
# javax.websocket needs annotations
annotations

[lib]
lib/websocket/*.jar

[jpms]
# The implementation needs to access method handles in
# classes that are in the web application classloader.
add-reads: org.eclipse.jetty.websocket.javax.common=ALL-UNNAMED
