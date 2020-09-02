# DO NOT EDIT - See: https://www.eclipse.org/jetty/documentation/current/startup-modules.html

[description]
Enable first level session cache. If this module is not enabled, sessions will
use the DefaultSessionCache by default, so enabling via this module is only needed
if the configuration properties need to be changed from their defaults.

[tags]
session

[provides]
session-cache

[depends]
sessions

[xml]
etc/sessions/session-cache-hash.xml

[ini-template]
#jetty.session.evictionPolicy=-1
#jetty.session.saveOnInactiveEvict=false
#jetty.session.saveOnCreate=false
#jetty.session.removeUnloadableSessions=false
#jetty.session.flushOnResponseCommit=false
#jetty.session.invalidateOnShutdown=false
