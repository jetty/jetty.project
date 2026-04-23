[description]
Enables Servlet resource injection. 

[environment]
<inherit>

[depend]
server
jndi
plus
ee/common-security
ee/common-webapp

[lib]
lib/jetty-ee-plus-${jetty.version}.jar
lib/@jakarta.transaction:jakarta.transaction-api@
lib/@jakarta.interceptor:jakarta.interceptor-api@
lib/@jakarta.enterprise:jakarta.enterprise.cdi-api@
lib/@jakarta.inject:jakarta.inject-api@
lib/@jakarta.enterprise:jakarta.enterprise.lang-model@
