[description]
Enables and configures the Server ThreadPool with support for virtual threads to be used for all threads.
There is some risk of CPU pinning with this configuration.  Only supported in Java 21 or later.

[depends]
logging

[provides]
threadpool

[xml]
etc/jetty-threadpool-all-virtual.xml

[ini-template]
# tag::documentation[]
## Platform threads name prefix.
#jetty.threadPool.namePrefix=vtp<hashCode>

# end::documentation[]
