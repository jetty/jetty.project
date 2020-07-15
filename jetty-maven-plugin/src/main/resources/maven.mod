# DO NOT EDIT - See: https://www.eclipse.org/jetty/documentation/current/startup-modules.html

[description]
Enables an unassembled maven webapp to run in a jetty distro

[depends]
server
webapp
annotations

[lib]
lib/maven/**.jar

[xml]
etc/jetty-maven.xml
