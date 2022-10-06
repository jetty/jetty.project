# DO NOT EDIT - See: https://www.eclipse.org/jetty/documentation/current/startup-modules.html

[description]
Demo Spec webapp

[environment]
ee8

[tags]
demo
webapp

[depends]
ee8-deploy
ee8-jaas
jdbc
ee8-jsp
ee8-annotations
ext

[files]
basehome:modules/demo.d/ee8-demo-jaas.xml|webapps/ee8-demo-jaas.xml
basehome:modules/demo.d/ee8-demo-jaas.properties|webapps/ee8-demo-jaas.properties
basehome:modules/demo.d/ee8-demo-login.properties|etc/ee8-demo-login.properties
maven://org.eclipse.jetty.ee8.demos/jetty-ee8-demo-jaas-webapp/${jetty.version}/war|webapps/ee8-demo-jaas.war

[ini]
# Enable security via jaas, and configure it
jetty.jaas.login.conf?=etc/demo-login.conf
