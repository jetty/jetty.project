[description]
Installs the Conscrypt JSSE provider.

[tags]
3rdparty

[depend]
ssl

[provides]
alpn-impl

[files]
maven://org.conscrypt/conscrypt-openjdk-uber/${conscrypt.version}|lib/conscrypt/conscrypt-uber-${conscrypt.version}.jar
#maven://org.conscrypt/conscrypt-openjdk/${conscrypt.version}/jar/linux-x86_64|lib/conscrypt/conscrypt-${conscrypt.version}-linux-x86_64.jar
basehome:modules/conscrypt/jetty-conscrypt.xml|etc/jetty-conscrypt.xml

[xml]
etc/jetty-conscrypt.xml

[lib]
lib/conscrypt/conscrypt-uber-${conscrypt.version}.jar
lib/conscrypt/conscrypt-${conscrypt.version}-linux-x86_64.jar
lib/jetty-alpn-conscrypt-server-${jetty.version}.jar

[license]
Conscrypt is distributed under the Apache Licence 2.0
https://github.com/google/conscrypt/blob/master/LICENSE

[ini]
conscrypt.version?=@conscrypt.version@
jetty.sslContext.provider?=Conscrypt
