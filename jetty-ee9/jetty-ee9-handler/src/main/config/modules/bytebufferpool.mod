[description]
Configures the ByteBufferPool used by ServerConnectors.

[tags]
bytebufferpool

[xml]
etc/jetty-bytebufferpool.xml

[ini-template]
## Minimum capacity of a single ByteBuffer.
#jetty.byteBufferPool.minCapacity=0

## Maximum capacity of a single ByteBuffer.
## Requests for ByteBuffers larger than this value results
## in the ByteBuffer being allocated but not pooled.
#jetty.byteBufferPool.maxCapacity=65536

## Bucket capacity factor.
## ByteBuffers are allocated out of buckets that have
## a capacity that is multiple of this factor.
#jetty.byteBufferPool.factor=1024

## Maximum queue length for each bucket (-1 for unbounded).
#jetty.byteBufferPool.maxQueueLength=-1

## Maximum heap memory retainable by the pool (0 for heuristic, -1 for unlimited).
#jetty.byteBufferPool.maxHeapMemory=0

## Maximum direct memory retainable by the pool (0 for heuristic, -1 for unlimited).
#jetty.byteBufferPool.maxDirectMemory=0
