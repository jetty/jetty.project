# DO NOT EDIT - See: https://www.eclipse.org/jetty/documentation/current/startup-modules.html

[description]
A meta module to enable all EE8 demo modules.

[environment]
ee8

[tags]
demo

[depends]
http
https
http2
test-keystore
work
demo-root
ee8-demo-async-rest
ee8-demo-jaas
ee8-demo-jetty
ee8-demo-proxy
ee8-demo-rewrite
ee8-demo-jndi
ee8-demo-spec
ee8-demo-jsp

[ini-template]
# Websocket chat examples needs websocket enabled
# Don't start for all contexts (set to true in test.xml context)
org.eclipse.jetty.websocket.jsr356=false
