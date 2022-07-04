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
ee10-jsp
ee10-jstl
ee10-annotations
ext
ee10-servlets
ee10-websocket-jakarta
ee10-websocket-jetty
ee10-demo-realm

[files]
webapps/ee10-demo-jetty.d/
basehome:modules/demo.d/ee10-demo-jetty.xml|webapps/ee10-demo-jetty.xml
basehome:modules/demo.d/ee10-demo-jetty-override-web.xml|webapps/ee10-demo-jetty.d/ee10-demo-jetty-override-web.xml
maven://org.eclipse.jetty.ee10.demos/jetty-ee10-demo-jetty-webapp/${jetty.version}/war|webapps/ee10-demo-jetty.war
