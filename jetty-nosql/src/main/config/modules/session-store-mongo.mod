DO NOT EDIT - See: https://www.eclipse.org/jetty/documentation/current/startup-modules.html

[description]
Enables NoSql session management with a MongoDB driver.

[tags]
session

[provides]
session-store

[depend]
sessions
sessions/mongo/${connection-type}

[files]
maven://org.mongodb/mongo-java-driver/2.13.2|lib/nosql/mongo-java-driver-2.13.2.jar

[lib]
lib/jetty-nosql-${jetty.version}.jar
lib/nosql/*.jar

[license]
The java driver for the MongoDB document-based database system is hosted on GitHub and released under the Apache 2.0 license.
http://www.mongodb.org/
http://www.apache.org/licenses/LICENSE-2.0.html


[ini]
connection-type?=address

[ini-template]
#jetty.session.mongo.dbName=HttpSessions
#jetty.session.mongo.collectionName=jettySessions
#jetty.session.gracePeriod.seconds=3600
#jetty.session.savePeriod.seconds=0

connection-type=address
#jetty.session.mongo.host=localhost
#jetty.session.mongo.port=27017

#connection-type=uri
#jetty.session.mongo.connectionString=mongodb://localhost

