[description]
Enables Servlet resource injection. 

[environment]
ee10

[depend]
ee/common-plus
server
jndi
plus
ee10-security
ee10-webapp

[lib]
lib/jetty-ee10-plus-${jetty.version}.jar
lib/@jakarta.transaction:jakarta.transaction-api@
lib/@jakarta.interceptor:jakarta.interceptor-api@
lib/@jakarta.enterprise:jakarta.enterprise.cdi-api@
lib/@jakarta.inject:jakarta.inject-api@
lib/@jakarta.enterprise:jakarta.enterprise.lang-model@
