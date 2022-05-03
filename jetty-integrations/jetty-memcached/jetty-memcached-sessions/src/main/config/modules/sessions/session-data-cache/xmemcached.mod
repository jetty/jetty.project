# DO NOT EDIT - See: https://www.eclipse.org/jetty/documentation/current/startup-modules.html

[description]
Memcache cache for SessionData.

[tags]
session

[depends]
session-store
logging/slf4j

[files]
maven://com.googlecode.xmemcached/xmemcached/${xmemcached.version}|lib/xmemcached/xmemcached-${xmemcached.version}.jar

[ini]
xmemcached.version?=@xmemcached.version@

[lib]
lib/jetty-memcached-sessions-${jetty.version}.jar
lib/xmemcached/*.jar

[license]
Xmemcached is an open source project hosted on Github and released under the Apache 2.0 license.
https://github.com/killme2008/xmemcached
http://www.apache.org/licenses/LICENSE-2.0.html


[xml]
etc/sessions/session-data-cache/xmemcached.xml
