[description]
Enables Servlet resource injection. 

[environment]
ee10

[depend]
server
jndi
plus
ee10-security
ee10-webapp

[lib]
lib/jetty-ee10-plus-${jetty.version}.jar
lib/jakarta.transaction-api-@ee10.jakarta.transaction-api.version@.jar
lib/jakarta.interceptor-api-@ee10.jakarta.interceptor.api.version@.jar
lib/jakarta.enterprise.cdi-api-@ee10.jakarta.enterprise.cdi.api.version@.jar
lib/jakarta.inject-api-@ee10.jakarta.inject.api.version@.jar
lib/jakarta.enterprise.lang-model-@ee10.jakarta.enterprise.lang.model.version@.jar
