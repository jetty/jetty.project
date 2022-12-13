[description]
Enables and configures the Server ThreadPool.

[depends]
logging

[provides]
threadpool|default

[xml]
etc/jetty-threadpool.xml

[ini-template]
# tag::documentation[]
## Thread name prefix.
#jetty.threadPool.namePrefix=qtp<hashCode>

## Minimum number of pooled threads.
#jetty.threadPool.minThreads=10

## Maximum number of pooled threads.
#jetty.threadPool.maxThreads=200

## Number of reserved threads (-1 for heuristic).
#jetty.threadPool.reservedThreads=-1

## Whether to use virtual threads, if the runtime supports them.
## Deprecated, use Jetty module 'threadpool-virtual-preview' instead.
#jetty.threadPool.useVirtualThreads=false

## Thread idle timeout (in milliseconds).
#jetty.threadPool.idleTimeout=60000

## Whether to output a detailed dump.
#jetty.threadPool.detailedDump=false
# end::documentation[]
