[description]
Adds support for servlet specification webapplication to the server
classpath.  Without this, only Jetty specific handlers may be deployed.

[depend]
servlet
security

[xml]
etc/jetty-webapp.xml

[lib]
lib/jetty-webapp-${jetty.version}.jar


[ini-template]
## Add to the server wide default jars and packages protected or hidden from webapps.
## System classes are protected and cannot be overridden by a webapp.
## Server classes are hidden and cannot be seen by a webapp
## Lists of patterns are comma separated and may be either:
##  + a qualified classname e.g. 'com.acme.Foo' 
##  + a package name e.g. 'net.example.'
##  + a jar file e.g. 'file:${jetty.base}/lib/dependency.jar' 
##  + a directory of jars,resource or classes e.g. 'file:${jetty.base}/resources' 
##  + A pattern preceeded with a '-' is an exclusion, all other patterns are inclusions
##
## The +=, operator appends to a CSV list with a comma as needed.
##
#jetty.webapp.addSystemClasses+=,org.example.
#jetty.webapp.addServerClasses+=,org.example.
