DO NOT EDIT - See: https://www.eclipse.org/jetty/documentation/current/startup-modules.html

[description]
Serve static files from a directory for the "/.well-known" context path.

[tags]
handler

[depend]
server

[xml]
etc/well-known.xml

[files]
.well-known/

[ini]
jetty.wellknown.dir?=.well-known

[ini-template]
## Well Known Directory (relative to $jetty.base)
# jetty.wellknown.dir=.well-known
