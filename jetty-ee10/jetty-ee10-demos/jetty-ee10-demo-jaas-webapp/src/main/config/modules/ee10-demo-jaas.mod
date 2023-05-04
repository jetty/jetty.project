# DO NOT EDIT - See: https://www.eclipse.org/jetty/documentation/current/startup-modules.html

[description]
Demo EE10 JAAS webapp

[environment]
ee10

[tags]
demo
webapp

[depends]
demo-jaas
ee10-deploy
jaas
jdbc
ee10-jsp
ee10-annotations
ext

[files]
basehome:modules/demo.d/ee10-demo-jaas.xml|webapps/ee10-demo-jaas.xml
maven://org.eclipse.jetty.ee10.demos/jetty-ee10-demo-jaas-webapp/${jetty.version}/war|webapps/ee10-demo-jaas.war
