# DO NOT EDIT - See: https://www.eclipse.org/jetty/documentation/current/startup-modules.html

[description]
Configures the ByteBufferPool used by ServerConnectors whose bucket sizes increase exponentially instead of linearly.

[tags]
bytebufferpool

[provides]
bytebufferpool

[xml]
etc/jetty-bytebufferpool-logarithmic.xml

[ini-template]
### Server ByteBufferPool Configuration
## Minimum capacity to pool ByteBuffers
#jetty.byteBufferPool.minCapacity=0

## Maximum capacity to pool ByteBuffers
#jetty.byteBufferPool.maxCapacity=65536

## Maximum size for each bucket (-1 for unbounded)
#jetty.byteBufferPool.maxBucketSize=-1

## Maximum heap memory held idle by the pool (0 for heuristic, -1 for unlimited).
#jetty.byteBufferPool.maxHeapMemory=0

## Maximum direct memory held idle by the pool (0 for heuristic, -1 for unlimited).
#jetty.byteBufferPool.maxDirectMemory=0

## Maximum heap memory retained whilst in use by the pool (0 for heuristic, -1 for unlimited, -2 for no retained).
#jetty.byteBufferPool.retainedHeapMemory=0

## Maximum direct memory retained whilst in use by the pool (0 for heuristic, -1 for unlimited, -2 for no retained).
#jetty.byteBufferPool.retainedDirectMemory=0
