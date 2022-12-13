[description]
Enables support for clear-text HTTP/1.1 over Java 16 Unix-Domain server sockets.

[tag]
connector
unixdomain

[depends]
server

[lib]
lib/jetty-unixdomain-server-*.jar

[xml]
etc/jetty-unixdomain-http.xml

[ini-template]
# tag::documentation[]
## The number of acceptors (-1 picks a default value based on number of cores).
# jetty.unixdomain.acceptors=1

## The number of selectors (-1 picks a default value based on number of cores).
# jetty.unixdomain.selectors=-1

## The Unix-Domain path the ServerSocketChannel listens to.
# jetty.unixdomain.path=/tmp/jetty.sock

## The ServerSocketChannel accept queue backlog (0 picks the platform default).
# jetty.unixdomain.acceptQueueSize=0

## The SO_RCVBUF option for accepted SocketChannels (0 picks the platform default).
# jetty.unixdomain.acceptedReceiveBufferSize=0

## The SO_SNDBUF option for accepted SocketChannels (0 picks the platform default).
# jetty.unixdomain.acceptedSendBufferSize=0
# end::documentation[]
