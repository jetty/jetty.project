# DO NOT EDIT - See: https://www.eclipse.org/jetty/documentation/current/startup-modules.html

[description]
Demo JNDI Resources Webapp

[tags]
demo
webapp

[depends]
deploy
ext
jdbc
plus
demo-mock-resources

[files]
basehome:modules/demo.d/demo-jndi.xml|webapps/demo-jndi.xml
maven://org.eclipse.jetty.demos/demo-jndi-webapp/${jetty.version}/war|webapps/demo-jndi.war
maven://jakarta.mail/jakarta.mail-api/2.0.0/jar|lib/ext/jakarta.mail-api-2.0.0.jar
