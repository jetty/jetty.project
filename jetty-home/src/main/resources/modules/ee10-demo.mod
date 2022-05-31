# DO NOT EDIT - See: https://www.eclipse.org/jetty/documentation/current/startup-modules.html

[description]
A meta module to enable all demo modules.

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
demo-ee10-async-rest
demo-ee10-jaas
demo-ee10-jetty
demo-ee10-moved-context
demo-ee10-proxy
demo-ee10-rewrite
demo-ee10-jndi
demo-ee10-spec
demo-ee10-jsp
ee10-demo-root

[ini-template]
# Websocket chat examples needs websocket enabled
# Don't start for all contexts (set to true in test.xml context)
org.eclipse.jetty.websocket.jsr356=false
