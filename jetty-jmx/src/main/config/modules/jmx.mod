DO NOT EDIT - See: https://www.eclipse.org/jetty/documentation/current/startup-modules.html

[description]
Enables JMX instrumentation for server beans and 
enables JMX agent.

[depend]
server

[lib]
lib/jetty-jmx-${jetty.version}.jar

[xml]
etc/jetty-jmx.xml

[ini-template]
## Should the MBean cache beans from other classloaders (eg WebApp Loaders)
# jetty.jmx.cacheOtherClassLoaders=false
