# DO NOT EDIT - See: https://www.eclipse.org/jetty/documentation/current/startup-modules.html

[description]
Enables session persistent storage in files.

[tags]
session

[provides]
session-store

[depends]
sessions

[lib]
lib/jetty-file-${jetty.version}.jar

[xml]
etc/sessions/file/session-store.xml

[files]
sessions/

[ini-template]
jetty.session.file.storeDir=${jetty.base}/sessions
#jetty.session.file.deleteUnrestorableFiles=false
#jetty.session.gracePeriod.seconds=3600
#jetty.session.savePeriod.seconds=0
