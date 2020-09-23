# DO NOT EDIT - See: https://www.eclipse.org/jetty/documentation/current/startup-modules.html

[description]
Enables Spring configuration processing of Jetty XML files.
All Jetty-style XML files can optionally be written as Spring beans.2

[depend]
server

[lib]
lib/spring/*.jar

[ini-template]
## See http://www.eclipse.org/jetty/documentation/current/frameworks.html#framework-jetty-spring
## for information on how to complete spring configuration

