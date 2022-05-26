# DO NOT EDIT - See: https://www.eclipse.org/jetty/documentation/current/startup-modules.html

[description]
Adds support for servlet specification web applications to the server classpath.
Without this, only Jetty-specific handlers may be deployed.

[environment]
ee10

[depend]
ee10-servlet
ee10-security

[xml]
etc/jetty-ee10-webapp.xml

[lib]
lib/jetty-ee10-webapp-${jetty.version}.jar

[ini-template]
## Add to the server wide default jars and packages protected or hidden from webapps.
## System classes are protected and cannot be overridden by a webapp.
## Server classes are hidden and cannot be seen by a webapp
## Lists of patterns are comma separated and may be either:
##  + a qualified classname e.g. 'com.acme.Foo' 
##  + a package name e.g. 'net.example.'
##  + a jar file e.g. '${jetty.base.uri}/lib/dependency.jar' 
##  + a directory of jars,resource or classes e.g. '${jetty.base.uri}/resources' 
##  + A pattern preceded with a '-' is an exclusion, all other patterns are inclusions
##
## The +=, operator appends to a CSV list with a comma as needed.
##
#jetty.webapp.addSystemClasses+=,org.example.
#jetty.webapp.addServerClasses+=,org.example.

[ini]
contextHandlerClass=org.eclipse.jetty.ee10.webapp.WebAppContext

[jpms]
add-modules:java.instrument
