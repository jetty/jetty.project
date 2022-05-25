DO NOT EDIT - See: https://www.eclipse.org/jetty/documentation/current/startup-modules.html

[description]
Enable SecuredRedirectHandler to redirect all http requests to https on the secure port configured in the server.ini file.

[tags]
handler

[depend]
server

[xml]
etc/jetty-secure-redirect.xml

[ini-template]
## The redirect code to use in the response.
# jetty.secureredirect.code=302
