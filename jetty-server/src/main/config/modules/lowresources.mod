#
# Low Resources module
#

DEPEND=server

etc/jetty-lowresources.xml

INI=# lowresources.period=1050
INI=# lowresources.lowResourcesIdleTimeout=200
INI=# lowresources.monitorThreads=true
INI=# lowresources.maxConnections=0
INI=# lowresources.maxMemory=0
INI=# lowresources.maxLowResourcesTime=5000