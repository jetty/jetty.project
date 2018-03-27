DO NOT EDIT - See: https://www.eclipse.org/jetty/documentation/current/startup-modules.html

[description]
Enables JNDI and resource injection for webapplications 
and other servlet 3.x features not supported in the core
jetty webapps module.

[depend]
server
security
jndi
webapp
transactions

[lib]
lib/jetty-plus-${jetty.version}.jar

[xml]
etc/jetty-plus.xml
