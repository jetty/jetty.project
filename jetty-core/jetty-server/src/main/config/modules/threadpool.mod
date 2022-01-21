[description]
Enables and configures the Server ThreadPool.

[depends]
logging

[xml]
etc/jetty-threadpool.xml

[ini-template]
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
