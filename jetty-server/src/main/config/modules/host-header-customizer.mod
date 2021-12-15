# DO NOT EDIT - See: https://www.eclipse.org/jetty/documentation/current/startup-modules.html

[description]
Adds a Host Header Customizer to the HTTP Connector to enforce a
Request Host header on HTTP/1.0 and HTTP/2 requests.

[tags]
connector

[depend]
http

[xml]
etc/jetty-host-header-customizer.xml

[ini-template]
### HostHeaderCustomizer Configuration

## The Server Name to force on null Host headers
# jetty.hostheader.serverName=serverName

## The Server Port to force on null Host headers
# jetty.hostheader.serverPort=80
