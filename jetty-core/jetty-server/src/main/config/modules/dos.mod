# DO NOT EDIT THIS FILE - See: https://eclipse.dev/jetty/documentation/

[description]
Enables the DosHandler for the server.

[tags]
connector

[depend]
server

[xml]
etc/jetty-dos.xml

[ini-template]

## The algorithm to use for obtaining an Id from an Request: ID_FROM_REMOTE_ADDRESS, ID_FROM_REMOTE_PORT, ID_FROM_REMOTE_ADDRESS_PORT, ID_CONNECTION
#jetty.dos.id.type=ID_FROM_REMOTE_ADDRESS
#jetty.dos.id.class=org.eclipse.jetty.server.handler.DosHandler

## The class to use to create Tracker instances to track the rate of requests
#jetty.dos.trackerFactory=org.eclipse.jetty.server.handler.DoSHandler$LeakingBucketTrackerFactory

## The maximum requests per second per client
#jetty.dos.trackerFactory.maxRequestsPerSecond=100

## The Handler class to use to reject DOS requests
#jetty.dos.rejectHandler=org.eclipse.jetty.server.handler.DoSHandler.InfiniteLeakingBucketTrackerFactory

## The period to delay dos requests before rejecting them.
#jetty.dos.rejectHandler.delayed.delayMs=1000

## The maximum number of requests to be held in the delay queue
#jetty.dos.rejectHandler.delayed.maxDelayQueue=1000

## The maximum number of clients to track; or -1 for a default value; or 0 for unlimited
#jetty.dos.maxTrackers=1000000

## Should untracked requests be allowed or rejected
#jetty.dos.rejectUntracked=false

## The status code used to reject requests; or 0 to abort the request; or -1 for a default
#jetty.dos.rejectStatus=429

## List of InetAddress patterns to include
#jetty.dos.include.inet=10.10.10-14.0-128

## List of InetAddressPatterns to exclude
#jetty.dos.exclude.inet=10.10.10-14.0-128

## List of path patterns to include
#jetty.dos.include.path=/context/*

## List of path to exclude
#jetty.dos.exclude.path=/context/*

