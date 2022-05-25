# DO NOT EDIT - See: https://www.eclipse.org/jetty/documentation/current/startup-modules.html

[description]
Demo Jetty Webapp

[tags]
demo
webapp

[environment]
ee10

[depends]
ee10-deploy
jdbc
jsp
jstl
ee10-annotations
ext
ee10-servlets
ee10-websocket-jakarta
ee10-websocket-jetty
ee10-demo-realm

[files]
webapps/demo-ee10-jetty.d/
basehome:modules/demo.d/demo-ee10-jetty.xml|webapps-ee10/demo-ee10-jetty.xml
basehome:modules/demo.d/demo-ee10-jetty-override-web.xml|webapps-ee10/demo-ee10-jetty.d/demo-ee10-jetty-override-web.xml
maven://org.eclipse.jetty.demos/demo-ee10-jetty-webapp/${jetty.version}/war|webapps-ee10/demo-ee10-jetty.war
