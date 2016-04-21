[description]
Enables JDBC session storage.


[name]
jdbc-session-store



[xml]
etc/jetty-jdbc-session-store.xml


[depend]
sessions
sessions/jdbc/${db-connection-type}



[ini]
db-connection-type=datasource



##
##JDBC Session properties
##

#jetty.session.gracePeriod.seconds=3600

## Connection type:driver
#db-connection-type=driver
#jetty.session.driverClass=
#jetty.session.driverUrl=

## Connection type:Datasource
#db-connection-type=datasource
#jetty.session.datasourceName=/jdbc/sessions

## Session table schema
#jetty.sessionTableSchema.accessTimeColumn=accessTime
#jetty.sessionTableSchema.contextPathColumn=contextPath
#jetty.sessionTableSchema.cookieTimeColumn=cookieTime
#jetty.sessionTableSchema.createTimeColumn=createTime
#jetty.sessionTableSchema.expiryTimeColumn=expiryTime
#jetty.sessionTableSchema.lastAccessTimeColumn=lastAccessTime
#jetty.sessionTableSchema.lastSavedTimeColumn=lastSavedTime
#jetty.sessionTableSchema.idColumn="sessionId
#jetty.sessionTableSchema.lastNodeColumn=lastNode
#jetty.sessionTableSchema.virtualHostColumn=virtualHost
#jetty.sessionTableSchema.maxIntervalColumn=maxInterval
#jetty.sessionTableSchema.mapColumn=map
#jetty.sessionTableSchema.table=JettySessions




