[depend]
ssl
npn

[lib]
lib/spdy/*.jar

[xml]
etc/jetty-ssl.xml
etc/jetty-spdy.xml

[ini-template]
spdy.port=8443
spdy.timeout=30000
#spdy.initialWindowSize=65536
