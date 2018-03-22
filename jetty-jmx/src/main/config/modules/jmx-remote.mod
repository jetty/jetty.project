DO NOT EDIT - See: https://www.eclipse.org/jetty/documentation/current/startup-modules.html

[description]
Enables remote RMI access to JMX

[depend]
jmx

[xml]
etc/jetty-jmx-remote.xml

[ini-template]
## The host/address to bind the RMI server to.
# jetty.jmxremote.rmiserverhost=localhost

## The port the RMI server listens to (0 means a random port is chosen).
# jetty.jmxremote.rmiserverport=1099

## The host/address to bind the RMI registry to.
# jetty.jmxremote.rmiregistryhost=localhost

## The port the RMI registry listens to.
# jetty.jmxremote.rmiregistryport=1099
