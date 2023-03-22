[description]
Enables and configures the Server ThreadPool with support for virtual threads.

[exec]
--enable-preview

[depends]
logging

[provides]
threadpool

[xml]
etc/jetty-threadpool-virtual-preview.xml

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

## Whether to output a detailed dump.
#jetty.threadPool.detailedDump=false

## Virtual threads name prefix.
#jetty.threadPool.virtual.namePrefix=qtp<hashCode>-virtual-

## Whether virtual threads are allowed to set thread locals.
#jetty.threadPool.virtual.allowSetThreadLocals=true

## Whether virtual threads inherits the values of inheritable thread locals.
#jetty.threadPool.virtual.inheritInheritableThreadLocals=true
# end::documentation[]
