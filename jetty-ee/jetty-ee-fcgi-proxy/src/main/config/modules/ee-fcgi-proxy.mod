[description]
Enables support for EECommon FastCGI proxying.

[environment]
<inherit>

[tags]
fcgi
proxy

[depends]
fcgi

[lib]
lib/jetty-ee-fcgi-proxy-${jetty.version}.jar
lib/jetty-ee-proxy-${jetty.version}.jar
