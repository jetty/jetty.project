# DO NOT EDIT - See: https://www.eclipse.org/jetty/documentation/current/startup-modules.html

[description]
A meta module to enable all demo modules.

[tags]
demo

[depends]
http
https
http2
test-keystore
work
demo-root
demo-async-rest
demo-proxy
demo-spec

demo-jetty
demo-rewrite
demo-moved-context

[ini-template]
# Websocket chat examples needs websocket enabled
# Don't start for all contexts (set to true in test.xml context)
org.eclipse.jetty.websocket.jsr356=false

