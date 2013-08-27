
# SSL Keystore module

DEPEND=server
etc/jetty-ssl.xml

INI=jetty.keystore=etc/keystore
INI=jetty.keystore.password=OBF:1vny1zlo1x8e1vnw1vn61x8g1zlu1vn4
INI=jetty.keymanager.password=OBF:1u2u1wml1z7s1z7a1wnl1u2g
INI=jetty.truststore=etc/keystore
INI=jetty.truststore.password=OBF:1vny1zlo1x8e1vnw1vn61x8g1zlu1vn4
INI=jetty.secure.port=8443
