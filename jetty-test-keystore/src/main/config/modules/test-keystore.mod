[description]
Test keystore with self-signed SSL Certificate.
DO NOT USE IN PRODUCTION!!!

[tags]
demo
ssl

[depend]
ssl

[lib]
lib/jetty-test-keystore-${jetty.version}.jar

[xml]
etc/jetty-test-keystore.xml

[ini]
jetty.sslContext.keyStorePath?=etc/test-keystore.p12
jetty.sslContext.keyStoreType?=PKCS12
jetty.sslContext.keyStorePassword?=OBF:1vny1zlo1x8e1vnw1vn61x8g1zlu1vn4
jetty.sslContext.trustStorePath?=etc/test-keystore.p12
jetty.sslContext.trustStoreType?=PKCS12
jetty.sslContext.keyStorePassword?=OBF:1vny1zlo1x8e1vnw1vn61x8g1zlu1vn4
