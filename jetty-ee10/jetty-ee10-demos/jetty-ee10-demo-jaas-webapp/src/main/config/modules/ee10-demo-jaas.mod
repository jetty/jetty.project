# DO NOT EDIT - See: https://www.eclipse.org/jetty/documentation/current/startup-modules.html

[description]
Demo Spec webapp

[environment]
ee10

[tags]
demo
webapp

[depends]
ee10-deploy
ee10-jaas
jdbc
ee10-jsp
ee10-annotations
ext

[files]
basehome:modules/demo.d/ee10-demo-jaas.xml|webapps/ee10-demo-jaas.xml
basehome:modules/demo.d/ee10-demo-login.properties|etc/ee10-demo-login.properties
maven://org.eclipse.jetty.ee10.demos/jetty-ee10-demo-jaas-webapp/${jetty.version}/war|webapps/ee10-demo-jaas.war

[ini]
# Enable security via jaas, and configure it
jetty.jaas.login.conf?=etc/demo-login.conf
