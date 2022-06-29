[description]
Configures the ByteBufferPool used by ServerConnectors.
Use module "bytebufferpool-logarithmic" for a pool may hold less granulated sized buffers.

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

## Maximum size for each bucket (-1 for unbounded).
#jetty.byteBufferPool.maxBucketSize=-1

## Maximum heap memory held idle by the pool (0 for heuristic, -1 for unlimited).
#jetty.byteBufferPool.maxHeapMemory=0

## Maximum direct memory held idle by the pool (0 for heuristic, -1 for unlimited).
#jetty.byteBufferPool.maxDirectMemory=0

## Maximum heap memory retained whilst in use by the pool (0 for heuristic, -1 for unlimited, -2 for no retained).
#jetty.byteBufferPool.retainedHeapMemory=0

## Maximum direct memory retained whilst in use by the pool (0 for heuristic, -1 for unlimited, -2 for no retained).
#jetty.byteBufferPool.retainedDirectMemory=0