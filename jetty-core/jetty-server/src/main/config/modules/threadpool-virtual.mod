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
#jetty.threadPool.namePrefix=qtp<hashCode>

## Minimum number of pooled threads.
#jetty.threadPool.minThreads=10

## Maximum number of pooled threads.
#jetty.threadPool.maxThreads=200

## Number of reserved threads (-1 for heuristic).
#jetty.threadPool.reservedThreads=-1

## Thread idle timeout (in milliseconds).
#jetty.threadPool.idleTimeout=60000

## The max number of idle threads that can be evicted in one idleTimeout period.
#jetty.threadPool.maxEvictCount=1

## Whether to output a detailed dump.
#jetty.threadPool.detailedDump=false

## Virtual threads name prefix.
#jetty.threadPool.virtual.namePrefix=qtp<hashCode>-virtual-

## Whether virtual threads inherits the values of inheritable thread locals.
#jetty.threadPool.virtual.inheritInheritableThreadLocals=true
# end::documentation[]
