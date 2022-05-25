# DO NOT EDIT - See: https://www.eclipse.org/jetty/documentation/current/startup-modules.html

[description]
Demo JNDI Resources Webapp

[environment]
ee9

[tags]
demo
webapp

[depends]
ee9-deploy
ext
jdbc
ee9-plus
demo-ee9-mock-resources

[files]
basehome:modules/demo.d/demo-ee9-jndi.xml|webapps-ee9/demo-ee9-jndi.xml
maven://org.eclipse.jetty.demos/demo-ee9-jndi-webapp/${jetty.version}/war|webapps-ee9/demo-ee9-jndi.war
maven://jakarta.mail/jakarta.mail-api/2.0.0/jar|lib/ext/jakarta.mail-api-2.0.0.jar
