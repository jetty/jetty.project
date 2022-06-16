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
ee10-demo-mock-resources

[files]
basehome:modules/demo.d/ee10-demo-jndi.xml|webapps-ee10/ee10-demo-jndi.xml
maven://org.eclipse.jetty.ee10.demos/ee10-demo-jndi-webapp/${jetty.version}/war|webapps-ee10/ee10-demo-jndi.war
maven://jakarta.mail/jakarta.mail-api/2.0.0/jar|lib/ext/jakarta.mail-api-2.0.0.jar
