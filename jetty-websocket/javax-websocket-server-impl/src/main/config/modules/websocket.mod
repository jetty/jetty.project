# DO NOT EDIT - See: https://jetty.org/docs/9/startup-modules.html

[description]
Enable websockets for deployed web applications

[depend]
# websocket client needs jetty-client
client
# javax.websocket needs annotations
annotations

[lib]
lib/websocket/*.jar


