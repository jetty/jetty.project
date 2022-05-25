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
jsp
jstl
ee9-annotations
ext
ee9-servlets
websocket-jakarta
websocket-jetty
demo-ee9-realm

[files]
webapps-ee9/demo-jetty.d/
basehome:modules/demo.d/demo-ee9-jetty.xml|webapps-ee9/demo-ee9-jetty.xml
basehome:modules/demo.d/demo-ee9-jetty-override-web.xml|webapps-ee9/demo-ee9-jetty.d/demo-ee9-jetty-override-web.xml
maven://org.eclipse.jetty.ee9.demos/demo-ee9-jetty-webapp/${jetty.version}/war|webapps-ee9/demo-ee9-jetty.war
