[description]
Installs ThreadLimitHandler at the root of the `Handler` tree, to limit
the number of requests per IP address, for denial-of-service protection.

[tags]
server

[before]
compression
gzip

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
#end::documentation[]
