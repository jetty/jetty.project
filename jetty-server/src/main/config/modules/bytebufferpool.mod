# DO NOT EDIT - See: https://jetty.org/docs/9/startup-modules.html

[description]
Configures the ByteBufferPool used by ServerConnectors.

[tags]
bytebufferpool

[xml]
etc/jetty-bytebufferpool.xml

[ini-template]
### Server ByteBufferPool Configuration
## Minimum capacity to pool ByteBuffers
#jetty.byteBufferPool.minCapacity=0

## Maximum capacity to pool ByteBuffers
#jetty.byteBufferPool.maxCapacity=65536

## Capacity factor
#jetty.byteBufferPool.factor=1024

## Maximum queue length for each bucket (-1 for unbounded)
#jetty.byteBufferPool.maxQueueLength=-1

## Maximum heap memory retainable by the pool (0 for heuristic, -1 for unlimited)
#jetty.byteBufferPool.maxHeapMemory=0

## Maximum direct memory retainable by the pool (0 for heuristic, -1 for unlimited)
#jetty.byteBufferPool.maxDirectMemory=0
