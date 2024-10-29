[description]
Enables the StateTrackingHandler as the outermost Handler, to track
the state of various Handler/Request/Response asynchronous APIs.

[tags]
server
debug

[depends]
server

[before]
cross-origin
graceful
gzip
secure-redirect
statistics
thread-limit

[xml]
etc/jetty-state-tracking.xml

[ini-template]
# tag::documentation[]
## The timeout in ms for the completion of the handle() callback.
# jetty.stateTracking.handlerCallbackTimeout=0

## Whether the handle() callback is completed in case of timeout.
# jetty.stateTracking.completeHandlerCallbackAtTimeout=false

## The timeout in ms for the execution of the demand callback.
# jetty.stateTracking.demandCallbackTimeout=0

## The timeout in ms for the execution of a response write.
# jetty.stateTracking.writeTimeout=0

## The timeout in ms for the execution of the response write callback.
# jetty.stateTracking.writeCallbackTimeout=0
# end::documentation[]
