# DO NOT EDIT - See: https://www.eclipse.org/jetty/documentation/current/startup-modules.html

[description]
Enables use of the apache implementation of JSP.

[environment]
ee10

[depend]
ee10-servlet
ee10-annotations

[lib]
lib/ee10-apache-jsp/*.jar
lib/jetty-ee10-apache-jsp-${jetty.version}.jar

