[description]
Test keystore with self-signed SSL Certificate.
DO NOT USE IN PRODUCTION!!!

[tags]
demo
ssl

[depend]
ssl

[files]
maven://org.bouncycastle/bcpkix-jdk15on/${bouncycastle.version}|lib/bouncycastle/bcpkix-jdk15on-${bouncycastle.version}.jar
maven://org.bouncycastle/bcprov-jdk15on/${bouncycastle.version}|lib/bouncycastle/bcprov-jdk15on-${bouncycastle.version}.jar
maven://org.bouncycastle/bcutil-jdk15to18/${bouncycastle.version}|lib/bouncycastle/bcutil-jdk15on-${bouncycastle.version}.jar

[lib]
lib/jetty-keystore-${jetty.version}.jar
lib/bouncycastle/bcpkix-jdk15on-${bouncycastle.version}.jar
lib/bouncycastle/bcprov-jdk15on-${bouncycastle.version}.jar
lib/bouncycastle/bcutil-jdk15on-${bouncycastle.version}.jar

[xml]
etc/jetty-test-keystore.xml

[ini]
bouncycastle.version?=@bouncycastle.version@
jetty.webapp.addServerClasses+=,${jetty.base.uri}/lib/bouncycastle/
jetty.sslContext.keyStorePath?=etc/test-keystore.p12
jetty.sslContext.keyStoreType?=PKCS12
jetty.sslContext.keyStorePassword?=OBF:1vny1zlo1x8e1vnw1vn61x8g1zlu1vn4
