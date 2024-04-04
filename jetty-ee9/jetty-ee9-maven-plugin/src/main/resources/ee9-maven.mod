# DO NOT EDIT THIS FILE - See: https://eclipse.dev/jetty/documentation/

[description]
Enables an un-assembled Maven webapp to run in a Jetty distribution.

[environment]
ee9

[depends]
server
ee9-webapp
ee9-annotations

[lib]
lib/jetty-maven-${jetty.version}.jar
lib/maven-ee9/**.jar

[xml]
etc/jetty-ee9-maven.xml
