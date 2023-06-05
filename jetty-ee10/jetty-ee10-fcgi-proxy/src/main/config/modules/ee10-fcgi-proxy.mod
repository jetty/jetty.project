[description]
Enables support for EE10 FastCGI proxying.

[environment]
ee10

[tags]
fcgi
proxy

[depends]
fcgi

[lib]
lib/jetty-ee10-fcgi-proxy-${jetty.version}.jar
lib/jetty-ee10-proxy-${jetty.version}.jar
