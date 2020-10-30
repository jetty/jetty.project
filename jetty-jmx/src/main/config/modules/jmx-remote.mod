[description]
Enables clear-text remote RMI access to platform MBeans.

[depend]
jmx

[xml]
etc/jetty-jmx-remote.xml

[ini-template]
# tag::documentation[]
## The host/address to bind the RMI server to.
# jetty.jmxremote.rmiserverhost=localhost

## The port the RMI server listens to (0 means a random port is chosen).
# jetty.jmxremote.rmiserverport=1099

## The host/address to bind the RMI registry to.
# jetty.jmxremote.rmiregistryhost=localhost

## The port the RMI registry listens to.
# jetty.jmxremote.rmiregistryport=1099

## The host name exported in the RMI stub.
-Djava.rmi.server.hostname=localhost
# end::documentation[]
