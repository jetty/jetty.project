# DO NOT EDIT - See: https://www.eclipse.org/jetty/documentation/current/startup-modules.html

[description]
A meta module to enable all EE10 demo modules.

[environment]
ee10

[tags]
demo

[depends]
http
https
http2
test-keystore
work
demo-root
ee10-demo-async-rest
ee10-demo-jaas
ee10-demo-jetty
ee10-demo-proxy
ee10-demo-rewrite
ee10-demo-jndi
ee10-demo-spec
ee10-demo-jsp

[ini-template]
# Websocket chat examples needs websocket enabled
# Don't start for all contexts (set to true in test.xml context)
org.eclipse.jetty.websocket.jsr356=false
