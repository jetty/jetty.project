#
# Jetty NoSql module
#

[depend]
webapp

[files]
maven://org.mongodb/mongo-java-driver/2.6.1|lib/nosql/mongo-java-driver-2.6.1.jar

[lib]
lib/jetty-nosql-${jetty.version}.jar
lib/nosql/*.jar

[xml]
etc/jetty-nosql.xml

[license]
The java driver for the MongoDB document-based database system is hosted on GitHub and released under the Apache 2.0 license.
http://www.mongodb.org/
http://www.apache.org/licenses/LICENSE-2.0.html

[ini-template]
## MongoDB SessionIdManager config

## Unique identifier for this node in the cluster
# jetty.nosqlSession.workerName=node1

## Interval in seconds between scavenging expired sessions
# jetty.nosqlSession.scavenge=1800

