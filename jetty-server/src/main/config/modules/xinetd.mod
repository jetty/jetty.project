#
# Xinetd module
#

[depend]
server

[xml]
etc/jetty-xinetd.xml

[ini-template]
## Xinetd Configuration
## See ${jetty.home}/etc/jetty-xinetd.xml for example service entry
jetty.xinetd.idleTimeout=300000
jetty.xinetd.acceptors=2
jetty.xinetd.statsOn=false

