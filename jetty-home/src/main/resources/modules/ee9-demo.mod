# DO NOT EDIT - See: https://www.eclipse.org/jetty/documentation/current/startup-modules.html

[description]
A meta module to enable all demo modules.

[environment]
ee9

[tags]
demo

[depends]
http
https
http2
test-keystore
work
ee9-demo-async-rest
ee9-demo-jaas
ee9-demo-jetty
ee9-demo-moved-context
ee9-demo-proxy
ee9-demo-rewrite
ee9-demo-jndi
ee9-demo-spec
ee9-demo-jsp
ee9-demo-root

[ini-template]
# Websocket chat examples needs websocket enabled
# Don't start for all contexts (set to true in test.xml context)
org.eclipse.jetty.websocket.jsr356=false
