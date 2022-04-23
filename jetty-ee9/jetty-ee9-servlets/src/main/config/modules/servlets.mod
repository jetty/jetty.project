# DO NOT EDIT - See: https://www.eclipse.org/jetty/documentation/current/startup-modules.html

[description]
Adds Jetty utility servlets and filters available to a webapp.
Puts org.eclipse.jetty.ee9.servlets on the server classpath (CGI, CrossOriginFilter, DosFilter,
MultiPartFilter, PushCacheFilter, QoSFilter, etc.) for use by all webapplications.

[depend]
servlet

[lib]
lib/jetty-servlets-${jetty.version}.jar

