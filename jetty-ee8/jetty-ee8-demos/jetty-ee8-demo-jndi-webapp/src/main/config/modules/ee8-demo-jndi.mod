# DO NOT EDIT - See: https://www.eclipse.org/jetty/documentation/current/startup-modules.html

[description]
Demo JNDI Resources Webapp

[environment]
ee8

[tags]
demo
webapp

[depends]
ee8-deploy
ext
jdbc
ee8-jndi
ee8-plus
ee8-demo-mock-resources

[files]
basehome:modules/demo.d/ee8-demo-jndi.xml|webapps/ee8-demo-jndi.xml
basehome:modules/demo.d/ee8-demo-jndi.properties|webapps/ee8-demo-jndi.properties
maven://org.eclipse.jetty.ee8.demos/jetty-ee8-demo-jndi-webapp/${jetty.version}/war|webapps/ee8-demo-jndi.war
maven://jakarta.mail/jakarta.mail-api/2.0.0/jar|lib/ee8/jakarta.mail-api-2.0.0.jar

[lib]
lib/ee8/jakarta.mail-api-2.0.0.jar
