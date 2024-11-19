[description]
Installs QoSHandler at the root of the `Handler` tree,
to limit the number of concurrent requests, for resource management.

[tags]
server

[depends]
server

[xml]
etc/jetty-qos.xml

[ini-template]
#tag::documentation[]
## The maximum number of concurrent requests allowed; use 0 for a default
## value calculated from the ThreadPool configuration or the number of CPU cores.
# jetty.qos.maxRequestCount=0

## The maximum number of requests that may be suspended.
# jetty.qos.maxSuspendedRequestCount=1024

## The maximum duration that a request may remain suspended, in milliseconds; use 0 for unlimited time.
# jetty.qos.maxSuspendDuration=0

## A comma-separated list of HTTP methods to include when matching a request.
# jetty.qos.include.method=

## A comma-separated list of HTTP methods to exclude when matching a request.
# jetty.qos.exclude.method=

## A comma-separated list of URI path patterns to include when matching a request.
# jetty.qos.include.path=

## A comma-separated list of URI path patterns to exclude when matching a request.
# jetty.qos.exclude.path=

## A comma-separated list of remote addresses patterns to include when matching a request.
# jetty.qos.include.inet=

## A comma-separated list of remote addresses patterns to exclude when matching a request.
# jetty.qos.exclude.inet=
#end::documentation[]
