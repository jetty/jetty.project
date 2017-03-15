[description]
Enables JDBC peristent/distributed session storage.

[tags]
session

[provides]
session-store

[depend]
sessions
sessions/jdbc/${db-connection-type}

[xml]
etc/sessions/jdbc/session-store.xml

[ini]
db-connection-type?=datasource

[ini-template]
##
##JDBC Session properties
##

#jetty.session.gracePeriod.seconds=3600

## Connection type:Datasource
db-connection-type=datasource
#jetty.session.jdbc.datasourceName=/jdbc/sessions

## Connection type:driver
#db-connection-type=driver
#jetty.session.jdbc.driverClass=
#jetty.session.jdbc.driverUrl=

## Session table schema
#jetty.session.jdbc.schema.accessTimeColumn=accessTime
#jetty.session.jdbc.schema.contextPathColumn=contextPath
#jetty.session.jdbc.schema.cookieTimeColumn=cookieTime
#jetty.session.jdbc.schema.createTimeColumn=createTime
#jetty.session.jdbc.schema.expiryTimeColumn=expiryTime
#jetty.session.jdbc.schema.lastAccessTimeColumn=lastAccessTime
#jetty.session.jdbc.schema.lastSavedTimeColumn=lastSavedTime
#jetty.session.jdbc.schema.idColumn=sessionId
#jetty.session.jdbc.schema.lastNodeColumn=lastNode
#jetty.session.jdbc.schema.virtualHostColumn=virtualHost
#jetty.session.jdbc.schema.maxIntervalColumn=maxInterval
#jetty.session.jdbc.schema.mapColumn=map
#jetty.session.jdbc.schema.table=JettySessions




