[description]
Installs the Conscrypt JSSE provider

[tags]
3rdparty

[depend]
ssl

[files]
maven://org.conscrypt/conscrypt-openjdk-uber/${conscrypt.version}|lib/conscrypt/conscrypt-uber-${conscrypt.version}.jar
basehome:modules/conscrypt/conscrypt.xml|etc/conscrypt.xml

[lib]
lib/conscrypt/**.jar

[xml]
etc/conscrypt.xml

[license]
Conscrypt is distributed under the Apache Licence 2.0
https://github.com/google/conscrypt/blob/master/LICENSE

[ini]
conscrypt.version?=1.0.0.RC8
jetty.sslContext.provider?=AndroidOpenSSL

