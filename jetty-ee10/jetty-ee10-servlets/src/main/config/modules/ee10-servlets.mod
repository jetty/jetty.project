# DO NOT EDIT - See: https://www.eclipse.org/jetty/documentation/current/startup-modules.html

[description]
Adds Jetty utility servlets and filters available to a webapp.
Puts org.eclipse.jetty.servlets on the server classpath (CGI, CrossOriginFilter, DosFilter,
MultiPartFilter, PushCacheFilter, QoSFilter, etc.) for use by all webapplications.

[environment]
ee10

[depend]
ee10-servlet

[lib]
lib/jetty-ee10-servlets-${jetty.version}.jar

