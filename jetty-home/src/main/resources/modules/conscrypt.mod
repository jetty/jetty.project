DO NOT EDIT - See: https://www.eclipse.org/jetty/documentation/current/startup-modules.html

[description]
Installs the Conscrypt JSSE provider

[tags]
3rdparty

[depend]
ssl

[provides]
alpn-impl

[files]
maven://org.conscrypt/conscrypt-openjdk-uber/${conscrypt.version}|lib/conscrypt/conscrypt-uber-${conscrypt.version}.jar
#maven://org.conscrypt/conscrypt-openjdk/${conscrypt.version}/jar/linux-x86_64|lib/conscrypt/conscrypt-${conscrypt.version}-linux-x86_64.jar
basehome:modules/conscrypt/conscrypt.xml|etc/conscrypt.xml

[xml]
etc/conscrypt.xml

[lib]
lib/conscrypt/**.jar
lib/jetty-alpn-conscrypt-server-${jetty.version}.jar

[license]
Conscrypt is distributed under the Apache Licence 2.0
https://github.com/google/conscrypt/blob/master/LICENSE

[ini]
conscrypt.version?=1.1.4
jetty.sslContext.provider?=Conscrypt

