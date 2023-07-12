# DO NOT EDIT THIS FILE
# See https://eclipse.dev/jetty/documentation/ > Operations Guide > Jetty Start Mechanism

[description]
Adds Jetty utility servlets and filters available to a webapp.
Puts org.eclipse.jetty.servlets on the server classpath (CGI, CrossOriginFilter, DosFilter,
MultiPartFilter, PushCacheFilter, QoSFilter, etc.) for use by all webapplications.

[depend]
servlet

[lib]
lib/jetty-servlets-${jetty.version}.jar

