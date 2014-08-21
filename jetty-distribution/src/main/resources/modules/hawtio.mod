#
# Hawtio x module
#

[depend]
stats
deploy
jmx

[xml]
etc/hawtio.xml

[files]
etc/hawtio/
lib/hawtio/
https://oss.sonatype.org/content/repositories/public/io/hawt/hawtio-default/1.4.16/hawtio-default-1.4.16.war|lib/hawtio/hawtio.war

[ini-template]

-Dhawtio.authenticationEnabled=false
-Dhawtio.dirname=/dirname
-Dhawtio.config.dir=${jetty.base}/etc/hawtio
