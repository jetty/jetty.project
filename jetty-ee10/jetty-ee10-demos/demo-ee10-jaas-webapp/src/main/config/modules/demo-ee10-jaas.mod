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
jsp
ee10-annotations
ext

[files]
basehome:modules/demo.d/demo-ee10-jaas.xml|webapps-ee10/demo-ee10-jaas.xml
basehome:modules/demo.d/demo-ee10-login.conf|etc/demo-ee10-login.conf
basehome:modules/demo.d/demo-ee10-login.properties|etc/demo-ee10-login.properties
maven://org.eclipse.jetty.demos/demo-ee10-jaas-webapp/${jetty.version}/war|webapps-ee10/demo-ee10-jaas.war

[ini]
# Enable security via jaas, and configure it
jetty.jaas.login.conf?=etc/demo-login.conf
