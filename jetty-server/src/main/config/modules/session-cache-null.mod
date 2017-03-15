[description]
A trivial SessionCache that does not actually cache sessions.

[tags]
session

[provides]
session-cache

[depends]
sessions

[xml]
etc/sessions/session-cache-null.xml

[ini-template]
#jetty.session.saveOnCreate=false
#jetty.session.removeUnloadableSessions=false
