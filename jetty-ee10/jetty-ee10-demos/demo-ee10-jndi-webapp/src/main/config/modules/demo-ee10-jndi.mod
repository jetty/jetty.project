# DO NOT EDIT - See: https://www.eclipse.org/jetty/documentation/current/startup-modules.html

[description]
Demo JNDI Resources Webapp

[environment]
ee10

[tags]
demo
webapp

[depends]
ee10-deploy
ext
jdbc
ee10-plus
demo-ee10-mock-resources

[files]
basehome:modules/demo.d/demo-ee10-jndi.xml|webapps-ee10/demo-ee10-jndi.xml
maven://org.eclipse.jetty.demos/demo-ee10-jndi-webapp/${jetty.version}/war|webapps-ee10/demo-ee10-jndi.war
maven://jakarta.mail/jakarta.mail-api/2.0.0/jar|lib/ext/jakarta.mail-api-2.0.0.jar
