# DO NOT EDIT THIS FILE - See: https://eclipse.dev/jetty/documentation/

[description]
Enables the UNIX setUID configuration.
The server may be started as root to open privileged ports/files before
changing to a restricted user (e.g. Jetty).

[depend]
server

[lib]
lib/setuid/jetty-setuid-jna-2.0.0.jar

[xml]
etc/jetty-setuid.xml

[ini-template]
## SetUID Configuration
# jetty.setuid.startServerAsPrivileged=false
# jetty.setuid.userName=jetty
# jetty.setuid.groupName=jetty
# jetty.setuid.umask=002
# jetty.setuid.clearSupplementalGroups=false
