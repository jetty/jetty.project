# DO NOT EDIT - See: https://jetty.org/docs/9/startup-modules.html

[description]
Enables JMX instrumentation for server beans and 
enables JMX agent.

[depend]
server

[lib]
lib/jetty-jmx-${jetty.version}.jar

[xml]
etc/jetty-jmx.xml

