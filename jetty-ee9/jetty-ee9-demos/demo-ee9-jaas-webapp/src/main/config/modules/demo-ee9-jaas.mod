# DO NOT EDIT - See: https://www.eclipse.org/jetty/documentation/current/startup-modules.html

[description]
Demo Spec webapp

[environment]
ee9

[tags]
demo
webapp

[depends]
ee9-deploy
ee9-jaas
jdbc
jsp
ee9-annotations
ext

[files]
basehome:modules/demo.d/demo-ee9-jaas.xml|webapps-ee9/demo-ee9-jaas.xml
basehome:modules/demo.d/demo-ee9-login.conf|etc/demo-ee9-login.conf
basehome:modules/demo.d/demo-ee9-login.properties|etc/demo-ee9-login.properties
maven://org.eclipse.jetty.demos/demo-ee9-jaas-webapp/${jetty.version}/war|webapps-ee9/demo-ee9-jaas.war

[ini]
# Enable security via jaas, and configure it
jetty.jaas.login.conf?=etc/demo-login.conf
