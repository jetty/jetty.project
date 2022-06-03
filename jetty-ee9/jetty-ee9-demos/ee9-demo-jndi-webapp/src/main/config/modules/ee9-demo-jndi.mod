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
ee9-demo-mock-resources

[files]
basehome:modules/ee9-demo.d/ee9-demo-jndi.xml|webapps-ee9/ee9-demo-jndi.xml
maven://org.eclipse.jetty.ee9.demos/ee9-demo-jndi-webapp/${jetty.version}/war|webapps-ee9/ee9-demo-jndi.war
maven://jakarta.mail/jakarta.mail-api/2.0.0/jar|lib/ext/jakarta.mail-api-2.0.0.jar
