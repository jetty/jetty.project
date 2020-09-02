# DO NOT EDIT - See: https://www.eclipse.org/jetty/documentation/current/startup-modules.html

[description]
Enables session persistent storage in files.

[tags]
session

[provides]
session-store

[depends]
sessions

[xml]
etc/sessions/file/session-store.xml

[files]
sessions/

[ini-template]
jetty.session.file.storeDir=${jetty.base}/sessions
#jetty.session.file.deleteUnrestorableFiles=false
#jetty.session.savePeriod.seconds=0