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

[ini-template]
# tag::documentation[]
## Well Known Directory (relative to $JETTY_BASE if relative path, otherwise it is an absolute path).
# jetty.wellknown.dir=.well-known
# end::documentation[]
