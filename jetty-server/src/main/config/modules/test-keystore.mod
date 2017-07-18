[description]
Test keystore with test SSL Certificate.
DO NOT USE IN PRODUCTION!!!

[tags]
ssl

[depend]
ssl

[files]
basehome:modules/test-keystore/keystore|etc/test-keystore

[ini]
jetty.sslContext.keyStorePath?=etc/test-keystore
jetty.sslContext.trustStorePath?=etc/test-keystore
jetty.sslContext.keyStorePassword?=OBF:1vny1zlo1x8e1vnw1vn61x8g1zlu1vn4
jetty.sslContext.keyStoreType?=JKS
jetty.sslContext.keyManagerPassword?=OBF:1u2u1wml1z7s1z7a1wnl1u2g
jetty.sslContext.trustStorePassword?=OBF:1vny1zlo1x8e1vnw1vn61x8g1zlu1vn4
jetty.sslContext.trustStoreType?=JKS
