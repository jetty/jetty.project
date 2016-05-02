[description]
Enables session persistent storage in files.

[provides]
session-store

[depends]
sessions

[xml]
etc/sessions/file/session-store.xml

[files]
sessions/

[ini-template]
jetty.session.storeDir=${jetty.base}/sessions
#jetty.session.deleteUnrestorableFiles=false

