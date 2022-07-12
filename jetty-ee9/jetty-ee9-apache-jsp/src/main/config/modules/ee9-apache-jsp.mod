# DO NOT EDIT - See: https://www.eclipse.org/jetty/documentation/current/startup-modules.html

[description]
Enables use of the apache implementation of JSP.

[environment]
ee9

[depend]
ee9-servlet
ee9-annotations

[lib]
lib/ee9-apache-jsp/*.jar
lib/jetty-ee9-apache-jsp-${jetty.version}.jar
