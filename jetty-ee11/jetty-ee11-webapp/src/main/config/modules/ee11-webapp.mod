# DO NOT EDIT THIS FILE - See: https://jetty.org/docs/

[description]
# tag::description[]
This module enables deployment of Java Servlet web applications.
# end::description[]

[environment]
ee11

[depend]
ee-webapp
ee11-servlet
ee11-security

[xml]
etc/jetty-ee11-webapp.xml

[lib]
lib/jetty-ee11-webapp-${jetty.version}.jar

[ini-template]
# tag::ini-template[]
## Add to the environment wide default jars and packages protected or hidden from webapps.
## Protected (aka System) classes cannot be overridden by a webapp.
## Hidden (aka Server) classes cannot be seen by a webapp
## Lists of patterns are comma separated and may be either:
##  + a qualified classname e.g. 'com.acme.Foo' 
##  + a package name e.g. 'net.example.'
##  + a jar file e.g. '${jetty.base.uri}/lib/dependency.jar' 
##  + a directory of jars,resource or classes e.g. '${jetty.base.uri}/resources' 
##  + A pattern preceded with a '-' is an exclusion, all other patterns are inclusions
##
## The +=, operator appends to a CSV list with a comma as needed.
##
#jetty.webapp.addProtectedClasses+=,org.example.
#jetty.webapp.addHiddenClasses+=,org.example.
# end::ini-template[]

[ini]
contextHandlerClass?=org.eclipse.jetty.ee11.webapp.WebAppContext

[jpms]
add-modules:java.instrument
