[description]
Enables Servlet resource injection. 

[environment]
ee11

[depend]
server
jndi
plus
ee11-security
ee11-webapp

[lib]
lib/jetty-ee11-plus-${jetty.version}.jar
lib/jakarta.transaction-api-@ee11.jakarta.transaction-api.version@.jar
lib/jakarta.interceptor-api-@ee11.jakarta.interceptor.api.version@.jar
lib/jakarta.enterprise.cdi-api-@ee11.jakarta.enterprise.cdi.api.version@.jar
lib/jakarta.inject-api-@ee11.jakarta.inject.api.version@.jar
lib/jakarta.enterprise.lang-model-@ee11.jakarta.enterprise.lang.model.version@.jar
