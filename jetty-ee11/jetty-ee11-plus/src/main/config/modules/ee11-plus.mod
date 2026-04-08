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
lib/@jakarta.transaction:jakarta.transaction-api@
lib/@jakarta.interceptor:jakarta.interceptor-api@
lib/@jakarta.enterprise:jakarta.enterprise.cdi-api@
lib/@jakarta.inject:jakarta.inject-api@
lib/@jakarta.enterprise:jakarta.enterprise.lang-model@
