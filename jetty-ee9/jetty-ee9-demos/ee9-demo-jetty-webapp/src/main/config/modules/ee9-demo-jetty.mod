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
ee9-demo-realm

[files]
webapps-ee9/demo-jetty.d/
basehome:modules/ee9-demo.d/ee9-demo-jetty.xml|webapps-ee9/ee9-demo-jetty.xml
basehome:modules/ee9-demo.d/ee9-demo-jetty-override-web.xml|webapps-ee9/ee9-demo-jetty.d/ee9-demo-jetty-override-web.xml
maven://org.eclipse.jetty.ee9.demos/ee9-demo-jetty-webapp/${jetty.version}/war|webapps-ee9/ee9-demo-jetty.war
