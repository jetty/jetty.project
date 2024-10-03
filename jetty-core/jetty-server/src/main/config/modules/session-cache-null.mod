# DO NOT EDIT THIS FILE - See: https://jetty.org/docs/

[description]
A SessionCache that does not actually cache sessions.

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
#jetty.session.flushOnResponseCommit=true
