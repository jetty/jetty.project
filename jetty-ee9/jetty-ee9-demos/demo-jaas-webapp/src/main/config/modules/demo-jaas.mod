# DO NOT EDIT - See: https://www.eclipse.org/jetty/documentation/current/startup-modules.html

[description]
Demo Spec webapp

[tags]
demo
webapp

[depends]
deploy
jaas
jdbc
jsp
annotations
ext

[files]
basehome:modules/demo.d/demo-jaas.xml|webapps/demo-jaas.xml
basehome:modules/demo.d/demo-login.conf|etc/demo-login.conf
basehome:modules/demo.d/demo-login.properties|etc/demo-login.properties
maven://org.eclipse.jetty.demos/demo-jaas-webapp/${jetty.version}/war|webapps/demo-jaas.war

[ini]
# Enable security via jaas, and configure it
jetty.jaas.login.conf?=etc/demo-login.conf
