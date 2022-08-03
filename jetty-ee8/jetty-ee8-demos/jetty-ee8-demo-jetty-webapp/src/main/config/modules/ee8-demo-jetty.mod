# DO NOT EDIT - See: https://www.eclipse.org/jetty/documentation/current/startup-modules.html

[description]
Demo Jetty Webapp

[environment]
ee8

[tags]
demo
webapp

[depends]
ee8-deploy
jdbc
ee8-jsp
ee8-jstl
ee8-annotations
ext
ee8-servlets
ee8-websocket-jakarta
ee8-websocket-jetty
ee8-demo-realm

[files]
webapps/demo-jetty.d/
basehome:modules/demo.d/ee8-demo-jetty.xml|webapps/ee8-demo-jetty.xml
basehome:modules/demo.d/ee8-demo-jetty-override-web.xml|webapps/ee8-demo-jetty.d/ee8-demo-jetty-override-web.xml
basehome:modules/demo.d/ee8-demo-jetty.properties|webapps/ee8-demo-jetty.properties
maven://org.eclipse.jetty.ee8.demos/jetty-ee8-demo-jetty-webapp/${jetty.version}/war|webapps/ee8-demo-jetty.war
