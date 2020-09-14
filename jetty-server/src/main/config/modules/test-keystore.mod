[description]
Test keystore with test SSL Certificate.
DO NOT USE IN PRODUCTION!!!

[tags]
demo
ssl

[depend]
ssl

[files]
basehome:modules/test-keystore/test-keystore.p12|etc/test-keystore.p12

[ini]
jetty.sslContext.keyStorePath?=etc/test-keystore.p12
jetty.sslContext.trustStorePath?=etc/test-keystore.p12
jetty.sslContext.keyStoreType?=PKCS12
jetty.sslContext.keyStorePassword?=OBF:1vny1zlo1x8e1vnw1vn61x8g1zlu1vn4
