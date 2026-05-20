[description]
Enables support for EE12 FastCGI proxying.

[environment]
ee12

[tags]
fcgi
proxy

[depends]
fcgi
ee/common-fcgi-proxy

[lib]
lib/jetty-ee12-fcgi-proxy-${jetty.version}.jar
lib/jetty-ee12-proxy-${jetty.version}.jar
