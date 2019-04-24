DO NOT EDIT - See: https://www.eclipse.org/jetty/documentation/current/startup-modules.html

[description]
Enable the InetAccessHandler to apply a include/exclude
control of the remote IP of requests.

[tags]
handler

[depend]
server

[xml]
etc/jetty-inetaccess.xml

[ini-template]

## List of InetAddress patterns to include
#jetty.inetaccess.include=127.0.0.1,127.0.0.2

## List of InetAddress patterns to exclude
#jetty.inetaccess.exclude=127.0.0.1,127.0.0.2

## List of Connector names to include
#jetty.inetaccess.includeConnectorNames=http

## List of Connector names to exclude
#jetty.inetaccess.excludeConnectorNames=tls

