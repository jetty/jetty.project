[description]
Enables Servlet resource injection. 

[environment]
ee12

[depend]
ee/common-plus
server
jndi
plus
ee12-security
ee12-webapp

[lib]
lib/jetty-ee12-plus-${jetty.version}.jar
lib/@jakarta.transaction:jakarta.transaction-api@
lib/@jakarta.interceptor:jakarta.interceptor-api@
lib/@jakarta.enterprise:jakarta.enterprise.cdi-api@
lib/@jakarta.inject:jakarta.inject-api@
lib/@jakarta.enterprise:jakarta.enterprise.lang-model@
