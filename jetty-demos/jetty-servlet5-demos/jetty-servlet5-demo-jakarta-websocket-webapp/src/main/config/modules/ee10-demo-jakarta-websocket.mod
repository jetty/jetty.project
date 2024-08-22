# DO NOT EDIT THIS FILE - See: https://jetty.org/docs/

[description]
Demo Jakarta WebSocket Webapp

[environment]
ee10

[tags]
demo
webapp

[depends]
ee10-deploy
ext
ee10-servlets
ee10-websocket-jakarta

[files]
basehome:modules/demo.d/ee10-demo-jakarta-websocket.xml|webapps/ee10-demo-jakarta-websocket.xml
basehome:modules/demo.d/ee10-demo-jakarta-websocket.properties|webapps/ee10-demo-jakarta-websocket.properties
maven://org.eclipse.jetty.demos/jetty-servlet5-demo-jakarta-websocket-webapp/${jetty.version}/war|webapps/ee10-demo-jakarta-websocket.war
