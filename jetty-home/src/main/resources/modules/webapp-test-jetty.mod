# DO NOT EDIT - See: https://www.eclipse.org/jetty/documentation/current/startup-modules.html

[description]
Download and deploy the Test Spec webapp demo.

[tags]
demo
webapp

[depends]
deploy
jdbc
jsp
annotations
test-realm
ext
servlets
websocket-javax
websocket-jetty

[files]
webapps/test-jetty.d/
basehome:modules/demo.d/test-jetty.xml|webapps/test-jetty.xml
maven://org.eclipse.jetty/test-jetty-webapp/${jetty.version}/war|webapps/test-jetty.war
basehome:modules/demo.d/test-jetty-override-web.xml|webapps/test-jetty.d/test-jetty-override-web.xml
