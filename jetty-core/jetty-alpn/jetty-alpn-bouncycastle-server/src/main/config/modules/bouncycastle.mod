[description]
Installs the Bouncy Castle JSSE provider.

[tags]
3rdparty
ssl
bouncycastle

[depend]
ssl

[provides]
alpn-impl

[files]
maven://org.bouncycastle/bcprov-jdk18on/${bouncycastle.version}|lib/bouncycastle/bcprov-jdk18on-${bouncycastle.version}.jar
maven://org.bouncycastle/bctls-jdk18on/${bouncycastle.version}|lib/bouncycastle/bctls-jdk18on-${bouncycastle.version}.jar
basehome:modules/bouncycastle/jetty-bouncycastle.xml|etc/jetty-bouncycastle.xml

[xml]
etc/jetty-bouncycastle.xml

[lib]
lib/bouncycastle/bcprov-jdk18on-${bouncycastle.version}.jar
lib/bouncycastle/bctls-jdk18on-${bouncycastle.version}.jar
lib/jetty-alpn-bouncycastle-server-${jetty.version}.jar

[license]
The Bouncy Castle libraries are distributed under the MIT license.
https://github.com/bcgit/bc-java/blob/main/LICENSE.md

[ini]
bouncycastle.version?=@bouncycastle.version@
jetty.sslContext.provider?=BCJSSE
