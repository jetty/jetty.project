[description]
Enables JDBC peristent/distributed session storage.

[provides]
session-store

[depend]
sessions
sessions/jdbc/${db-connection-type}

[xml]
etc/sessions/jdbc/session-store.xml

[ini]
db-connection-type=datasource

[ini-template]
##
##JDBC Session properties
##

#jetty.session.gracePeriod.seconds=3600

## Connection type:Datasource
db-connection-type=datasource
#jetty.session.datasourceName=/jdbc/sessions

## Connection type:driver
#db-connection-type=driver
#jetty.session.driverClass=
#jetty.session.driverUrl=

## Session table schema
#jetty.sessionTableSchema.accessTimeColumn=accessTime
#jetty.sessionTableSchema.contextPathColumn=contextPath
#jetty.sessionTableSchema.cookieTimeColumn=cookieTime
#jetty.sessionTableSchema.createTimeColumn=createTime
#jetty.sessionTableSchema.expiryTimeColumn=expiryTime
#jetty.sessionTableSchema.lastAccessTimeColumn=lastAccessTime
#jetty.sessionTableSchema.lastSavedTimeColumn=lastSavedTime
#jetty.sessionTableSchema.idColumn=sessionId
#jetty.sessionTableSchema.lastNodeColumn=lastNode
#jetty.sessionTableSchema.virtualHostColumn=virtualHost
#jetty.sessionTableSchema.maxIntervalColumn=maxInterval
#jetty.sessionTableSchema.mapColumn=map
#jetty.sessionTableSchema.table=JettySessions




