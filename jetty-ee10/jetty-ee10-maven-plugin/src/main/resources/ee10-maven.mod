# DO NOT EDIT - See: https://www.eclipse.org/jetty/documentation/current/startup-modules.html

[description]
Enables an un-assembled Maven webapp to run in a Jetty distribution.

[environment]
ee10

[depends]
server
ee10-webapp
ee10-annotations

[lib]
lib/maven-ee10/**.jar

[xml]
etc/jetty-ee10-maven.xml
