#
# Jetty Demo Module
#

DEPEND=jndi
DEPEND=jaas
DEPEND=rewrite
DEPEND=client
DEPEND=annotations
DEPEND=websocket
DEPEND=deploy

LIB=demo/lib/*.jar

demo/test-realm.xml
demo/jetty-demo.xml

INI=demo.realm=demo/realm.properties
INI=jaas.login.conf=demo/login.conf
