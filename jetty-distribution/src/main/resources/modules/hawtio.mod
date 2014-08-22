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

[license]
Hawtio is a redhat JBoss project released under the Apache License, v2.0
http://hawt.io/
http://github.com/hawtio/hawtio
http://www.apache.org/licenses/LICENSE-2.0.html

[ini-template]

-Dhawtio.authenticationEnabled=false
-Dhawtio.dirname=/dirname
-Dhawtio.config.dir=${jetty.base}/etc/hawtio
