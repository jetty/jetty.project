[description]
Enables Servlet 3.1 resource injection.

[environment]
ee9

[depend]
server
jndi
plus
ee9-security
ee9-webapp

[lib]
lib/jetty-ee9-plus-${jetty.version}.jar
lib/jakarta.transaction-api-@ee9.jakarta.transaction-api.version@.jar
lib/jakarta.interceptor-api-@ee9.jakarta.interceptor.api.version@.jar
lib/jakarta.enterprise.cdi-api-@ee9.jakarta.enterprise.cdi.api.version@.jar
lib/jakarta.inject-api-@ee9.jakarta.inject.api.version@.jar
lib/jakarta.enterprise.lang-model-@ee9.jakarta.enterprise.lang.model.version@.jar
