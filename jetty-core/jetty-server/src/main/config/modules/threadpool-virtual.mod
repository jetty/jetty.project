[description]
Enables and configures the Server ThreadPool with support for virtual threads in Java 21 or later.

[depends]
logging

[provides]
threadpool

[xml]
etc/jetty-threadpool-virtual.xml

[ini-template]
# tag::documentation[]
## Platform threads name prefix.
#jetty.threadPool.namePrefix=vtp<hashCode>

# end::documentation[]
