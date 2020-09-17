# DO NOT EDIT - See: https://www.eclipse.org/jetty/documentation/current/startup-modules.html

[description]
A meta module to enable all demo modules.

[tags]
demo

[depends]
http
https
http2
webapp-root
webapp-async-rest
webapp-test-spec
test-keystore
work
demo-rewrite
demo-moved-context

[files]
maven://org.eclipse.jetty.example-async-rest/example-async-rest-webapp/${jetty.version}/war|webapps/async-rest.war

[ini-template]
# Websocket chat examples needs websocket enabled
# Don't start for all contexts (set to true in test.xml context)
org.eclipse.jetty.websocket.jsr356=false

