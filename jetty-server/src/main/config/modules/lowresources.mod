DO NOT EDIT - See: https://www.eclipse.org/jetty/documentation/current/startup-modules.html

[description]
Enables a low resource monitor on the server
that can take actions if threads and/or connections
cross configured threshholds.

[depend]
server

[xml]
etc/jetty-lowresources.xml

[ini-template]
## Scan period to look for low resources (in milliseconds)
# jetty.lowresources.period=1000

## The idle timeout to apply to low resources (in milliseconds)
# jetty.lowresources.idleTimeout=1000

## Whether to monitor ThreadPool threads for low resources
# jetty.lowresources.monitorThreads=true

## Max number of connections allowed before being in low resources mode
# jetty.lowresources.maxConnections=0

## Max memory allowed before being in low resources mode (in bytes)
# jetty.lowresources.maxMemory=0

## Max time a resource may stay in low resource mode before actions are taken (in milliseconds)
# jetty.lowresources.maxLowResourcesTime=5000

## Accept new connections while in low resources
# jetty.lowresources.accepting=true
