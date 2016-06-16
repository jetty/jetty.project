[description]
Memcache cache for SessionData

[depends]
session-store

[files]
maven://com.googlecode.xmemcached/xmemcached/2.0.0|lib/xmemcached/xmemcached-2.0.0.jar
maven://org.slf4j/slf4j-api/1.6.6|lib/xmemcached/slf4j-api-1.6.6.jar

[lib]
lib/jetty-memcached-sessions-${jetty.version}.jar
lib/xmemcached/*.jar

[license]
Xmemcached is an open source project hosted on Github and released under the Apache 2.0 license.
https://github.com/killme2008/xmemcached
http://www.apache.org/licenses/LICENSE-2.0.html


[xml]
etc/sessions/session-data-cache/xmemcached.xml
