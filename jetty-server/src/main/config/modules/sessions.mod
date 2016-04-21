[description]
Enables session id management and scavenging.


[name]
sessions



[xml]
etc/jetty-sessions.xml


[ini-template]

## The name to uniquely identify this server instance
#jetty.sessionIdManager.workerName=node1

## Period between runs of the session scavenger (in seconds)
#jetty.sessionScavengeInterval.seconds=60
