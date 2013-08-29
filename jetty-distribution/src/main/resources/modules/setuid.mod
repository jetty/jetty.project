DEPEND=server
LIB=jetty-setuid-java-1.0.1.jar

etc/jetty-setuid.xml

INI=# jetty.startServerAsPrivileged=false
INI=# jetty.username=jetty
INI=# jetty.groupname=jetty
INI=# jetty.umask=002
