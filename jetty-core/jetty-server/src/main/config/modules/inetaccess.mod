# DO NOT EDIT THIS FILE - See: https://jetty.org/docs/

[description]
Enables the InetAccessHandler.
Applies an include/exclude control of the remote IP of requests.

[tags]
connector

[depend]
server

[files]
basehome:modules/inetaccess/jetty-inetaccess.xml|etc/jetty-inetaccess.xml

[xml]
etc/jetty-inetaccess.xml

[ini-template]

## List of InetAddress patterns to include (connectorName@addressPattern|pathSpec)
#jetty.inetaccess.include=http@127.0.0.1-127.0.0.2|/pathSpec,tls@,|/pathSpec2,127.0.0.20

## List of InetAddress patterns to exclude (connectorName@addressPattern|pathSpec)
#jetty.inetaccess.exclude=http@127.0.0.1-127.0.0.2|/pathSpec,tls@,|/pathSpec2,127.0.0.20

