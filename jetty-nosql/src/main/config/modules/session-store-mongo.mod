[description]
Enables NoSql session management with a MongoDB driver.

[tags]
session

[provides]
session-store

[depend]
sessions

[files]
maven://org.mongodb/mongo-java-driver/2.6.1|lib/nosql/mongo-java-driver-2.6.1.jar

[lib]
lib/jetty-nosql-${jetty.version}.jar
lib/nosql/*.jar

[license]
The java driver for the MongoDB document-based database system is hosted on GitHub and released under the Apache 2.0 license.
http://www.mongodb.org/
http://www.apache.org/licenses/LICENSE-2.0.html

[xml]
etc/sessions/mongo/session-store.xml

[ini-template]
#jetty.session.mongo.dbName=HttpSessions
#jetty.session.mongo.collectionName=jettySessions
#jetty.session.mongo.host=localhost
#jetty.session.mongo.port=27017
#jetty.session.gracePeriod.seconds=3600

