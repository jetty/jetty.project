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

[files]
basehome:modules/demo.d/demo-jndi.xml|webapps/demo-jndi.xml
maven://org.eclipse.jetty.demos/demo-jndi-webapp/${jetty.version}/war|webapps/demo-jndi.war
maven://org.eclipse.jetty.demos/demo-mock-resources/${jetty.version}/jar|lib/ext/demo-mock-resources-${jetty.version}.jar
maven://jakarta.mail/jakarta.mail-api/2.0.0-RC4/jar|lib/ext/jakarta.mail-api-2.0.0-RC4.jar
maven://jakarta.transaction/jakarta.transaction-api/2.0.0-RC1/jar|lib/ext/jakarta.transaction-api-2.0.0-RC1.jar
