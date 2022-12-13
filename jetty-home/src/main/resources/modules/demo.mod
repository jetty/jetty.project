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
demo-async-rest
demo-jaas
demo-jetty
demo-moved-context
demo-proxy
demo-rewrite
demo-root
demo-jndi
demo-spec
demo-jsp

[ini-template]
# Websocket chat examples needs websocket enabled
# Don't start for all contexts (set to true in test.xml context)
org.eclipse.jetty.websocket.jsr356=false
