
# SSL Keystore module

[depend]
server

[xml]
etc/jetty-ssl.xml

[files]
http://git.eclipse.org/c/jetty/org.eclipse.jetty.project.git/plain/jetty-server/src/main/config/etc/keystore:etc/keystore

[ini]
jetty.keystore=etc/keystore
jetty.keystore.password=OBF:1vny1zlo1x8e1vnw1vn61x8g1zlu1vn4
jetty.keymanager.password=OBF:1u2u1wml1z7s1z7a1wnl1u2g
jetty.truststore=etc/keystore
jetty.truststore.password=OBF:1vny1zlo1x8e1vnw1vn61x8g1zlu1vn4
jetty.secure.port=8443
