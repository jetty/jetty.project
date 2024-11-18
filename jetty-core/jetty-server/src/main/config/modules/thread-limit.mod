[description]
Installs ThreadLimitHandler at the root of the `Handler` tree, to limit
the number of requests per IP address, for denial-of-service protection.

[tags]
server

[depends]
server

[xml]
etc/jetty-thread-limit.xml

[ini-template]
#tag::documentation[]
## Select style of reverse proxy forwarded header.
# jetty.threadlimit.forwardedHeader=X-Forwarded-For
# jetty.threadlimit.forwardedHeader=Forwarded

## Whether thread limiting is enabled.
# jetty.threadlimit.enabled=true

## The thread limit per remote IP address.
# jetty.threadlimit.threadLimit=10

## A comma-separated list of HTTP methods to include when matching a request.
# jetty.threadlimit.include.method=

## A comma-separated list of HTTP methods to exclude when matching a request.
# jetty.threadlimit.exclude.method=

## A comma-separated list of URI path patterns to include when matching a request.
# jetty.threadlimit.include.path=

## A comma-separated list of URI path patterns to exclude when matching a request.
# jetty.threadlimit.exclude.path=

## A comma-separated list of remote addresses patterns to include when matching a request.
# jetty.threadlimit.include.inet=

## A comma-separated list of remote addresses patterns to exclude when matching a request.
# jetty.threadlimit.exclude.inet=
#end::documentation[]
