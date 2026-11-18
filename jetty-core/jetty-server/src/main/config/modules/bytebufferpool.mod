[description]
Configures the ByteBufferPool used by ServerConnectors.

[tags]
bytebufferpool

[provides]
bytebufferpool|default

[depends]
logging

[xml]
etc/jetty-bytebufferpool.xml

[ini-template]
## Maximum heap memory held idle by the pool (0 for heuristic, -1 for unlimited).
#jetty.byteBufferPool.maxHeapMemory=0

## Maximum direct memory held idle by the pool (0 for heuristic, -1 for unlimited).
#jetty.byteBufferPool.maxDirectMemory=0

## Whether statistics are enabled.
#jetty.byteBufferPool.statisticsEnabled=false
