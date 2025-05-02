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
lib/@jakarta.transaction:jakarta.transaction-api@
lib/@jakarta.interceptor:jakarta.interceptor-api@
lib/@jakarta.enterprise:jakarta.enterprise.cdi-api@
lib/@jakarta.inject:jakarta.inject-api@
lib/@jakarta.enterprise:jakarta.enterprise.lang-model@
