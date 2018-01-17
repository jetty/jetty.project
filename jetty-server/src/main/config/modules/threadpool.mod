[description]
Enables the Server thread pool.

[xml]
etc/jetty-threadpool.xml

[ini-template]

### Server Thread Pool Configuration
## Minimum Number of Threads
#jetty.threadPool.minThreads=10

## Maximum Number of Threads
#jetty.threadPool.maxThreads=200

## Thread Idle Timeout (in milliseconds)
#jetty.threadPool.idleTimeout=60000

## Detailed Dump Required?
#jetty.threadPool.detailedDump=false
