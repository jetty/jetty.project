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
maven://org.eclipse.jetty.orbit/javax.mail.glassfish/1.4.1.v201005082020/jar|lib/ext/javax.mail.glassfish-1.4.1.v201005082020.jar
maven://jakarta.transaction/jakarta.transaction-api/1.3.2/jar|lib/ext/jakarta.transaction-api-1.3.2.jar
