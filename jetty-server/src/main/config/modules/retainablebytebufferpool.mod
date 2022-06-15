[description]
Configures the RetainableByteBufferPool used by ServerConnectors.

[tags]
retainablebytebufferpool

[xml]
etc/jetty-retainablebytebufferpool.xml

[ini-template]
## Minimum capacity of a single ByteBuffer.
#jetty.retainableByteBufferPool.minCapacity=0

## Maximum capacity of a single ByteBuffer.
## Requests for ByteBuffers larger than this value results
## in the ByteBuffer being allocated but not pooled.
#jetty.retainableByteBufferPool.maxCapacity=65536

## Bucket capacity factor.
## ByteBuffers are allocated out of buckets that have
## a capacity that is multiple of this factor.
#jetty.retainableByteBufferPool.factor=1024

## Maximum number of ByteBuffers for each bucket.
#jetty.retainableByteBufferPool.maxBucketSize=2147483647

## Maximum heap memory bytes retainable by the pool (0 for heuristic, -1 for unlimited).
#jetty.retainableByteBufferPool.maxHeapMemory=0

## Maximum direct memory bytes retainable by the pool (0 for heuristic, -1 for unlimited).
#jetty.retainableByteBufferPool.maxDirectMemory=0
