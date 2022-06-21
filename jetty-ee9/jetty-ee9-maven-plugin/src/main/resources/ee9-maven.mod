# DO NOT EDIT - See: https://www.eclipse.org/jetty/documentation/current/startup-modules.html

[description]
Enables an un-assembled Maven webapp to run in a Jetty distribution.

[environment]
ee9

[depends]
server
ee9-webapp
ee9-annotations

[lib]
lib/maven-ee9/**.jar

[xml]
etc/jetty-ee9-maven.xml
