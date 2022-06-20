# DO NOT EDIT - See: https://www.eclipse.org/jetty/documentation/current/startup-modules.html

[description]
Enables an un-assembled Maven webapp to run in a Jetty distribution.

[depends]
server
ee10-webapp
ee10-annotations

[lib]
lib/maven/**.jar

[xml]
etc/jetty-maven.xml
