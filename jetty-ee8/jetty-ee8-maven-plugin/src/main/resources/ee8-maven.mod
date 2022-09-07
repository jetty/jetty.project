# DO NOT EDIT - See: https://www.eclipse.org/jetty/documentation/current/startup-modules.html

[description]
Enables an un-assembled Maven webapp to run in a Jetty distribution.

[environment]
ee8

[depends]
server
ee8-webapp
ee8-annotations

[lib]
lib/maven-ee8/**.jar

[xml]
etc/jetty-ee8-maven.xml
