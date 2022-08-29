# DO NOT EDIT - See: https://www.eclipse.org/jetty/documentation/current/startup-modules.html

[description]
Demo Core Handler

[tags]
demo
handler

[depends]
server

[files]
basehome:modules/demo.d/demo-handler.xml|etc/demo-handler.xml
maven://org.eclipse.jetty.demos/jetty-demo-handler/${jetty.version}/jar|lib/jetty-demo-handler.jar

[xml]
etc/demo-handler.xml

[lib]
lib/jetty-demo-handler.jar
