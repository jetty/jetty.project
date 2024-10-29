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
## Virtual threads name prefix.
#jetty.threadPool.namePrefix=vtp<hashCode>

## Maximum number of current virtual threads.
#jetty.threadPool.maxThreads=200

## Whether to track virtual threads so they appear
## in the dump even if they are unmounted.
#jetty.threadPool.tracking=false

## Whether to output virtual thread's stack traces in the dump.
#jetty.threadPool.detailedDump=false
# end::documentation[]
