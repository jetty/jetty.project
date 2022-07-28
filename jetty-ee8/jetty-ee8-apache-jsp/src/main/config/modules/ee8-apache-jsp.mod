# DO NOT EDIT - See: https://www.eclipse.org/jetty/documentation/current/startup-modules.html

[description]
Enables use of the apache implementation of JSP.

[environment]
ee8

[depend]
ee8-servlet
ee8-annotations

[lib]
lib/ee8-apache-jsp/*.jar
lib/jetty-ee8-apache-jsp-${jetty.version}.jar
