# DO NOT EDIT - See: https://www.eclipse.org/jetty/documentation/current/startup-modules.html

[description]
Adds Jetty utility servlets and filters available to a webapp.
Puts org.eclipse.jetty.ee8.servlets on the server classpath (CGI, CrossOriginFilter, DosFilter,
MultiPartFilter, PushCacheFilter, QoSFilter, etc.) for use by all webapplications.

[environment]
ee8

[depend]
servlet

[lib]
lib/jetty-ee8-servlets-${jetty.version}.jar

