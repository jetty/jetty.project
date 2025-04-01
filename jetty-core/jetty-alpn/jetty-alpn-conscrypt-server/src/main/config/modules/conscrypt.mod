[description]
Installs the Conscrypt JSSE provider.

[tags]
3rdparty
ssl
conscrypt

[depend]
ssl

[provides]
alpn-impl

[files]
maven://org.conscrypt/conscrypt-openjdk-uber/${conscrypt.version}|lib/conscrypt/conscrypt-uber-${conscrypt.version}.jar
basehome:modules/conscrypt/jetty-conscrypt.xml|etc/jetty-conscrypt.xml

[xml]
etc/jetty-conscrypt.xml

[lib]
lib/conscrypt/conscrypt-uber-${conscrypt.version}.jar
lib/jetty-alpn-conscrypt-server-${jetty.version}.jar

[license]
The Conscrypt libraries are distributed under the Apache Licence 2.0.
https://github.com/google/conscrypt/blob/master/LICENSE

[ini]
conscrypt.version?=@conscrypt.version@
jetty.sslContext.provider?=Conscrypt
