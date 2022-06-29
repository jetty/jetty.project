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
basehome:modules/demo.d/ee9-demo-jaas.xml|webapps/ee9-demo-jaas.xml
basehome:modules/demo.d/ee9-demo-jaas.properties|webapps/ee9-demo-jaas.properties
basehome:modules/demo.d/ee9-demo-login.conf|etc/ee9-demo-login.conf
basehome:modules/demo.d/ee9-demo-login.properties|etc/ee9-demo-login.properties
maven://org.eclipse.jetty.ee9.demos/jetty-ee9-demo-jaas-webapp/${jetty.version}/war|webapps/ee9-demo-jaas.war

[ini]
# Enable security via jaas, and configure it
jetty.jaas.login.conf?=etc/demo-login.conf
