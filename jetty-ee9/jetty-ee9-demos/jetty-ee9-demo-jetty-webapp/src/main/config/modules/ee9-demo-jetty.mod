# DO NOT EDIT - See: https://www.eclipse.org/jetty/documentation/current/startup-modules.html

[description]
Demo Jetty Webapp

[environment]
ee9

[tags]
demo
webapp

[depends]
ee9-deploy
jdbc
ee9-jsp
ee9-jstl
ee9-annotations
ext
ee9-servlets
ee9-websocket-jakarta
ee9-websocket-jetty
ee9-demo-realm

[files]
webapps/demo-jetty.d/
basehome:modules/demo.d/ee9-demo-jetty.xml|webapps/ee9-demo-jetty.xml
basehome:modules/demo.d/ee9-demo-jetty-override-web.xml|webapps/ee9-demo-jetty.d/ee9-demo-jetty-override-web.xml
basehome:modules/demo.d/ee9-demo-jetty.properties|webapps/ee9-demo-jetty.properties
maven://org.eclipse.jetty.ee9.demos/jetty-ee9-demo-jetty-webapp/${jetty.version}/war|webapps/ee9-demo-jetty.war
