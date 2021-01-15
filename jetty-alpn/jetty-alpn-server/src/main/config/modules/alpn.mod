[description]
Enables the handling of the ALPN (Application Layer Protocol Negotiation) TLS extension.

[tag]
connector
ssl
internal

[depend]
ssl
alpn-impl

[lib]
lib/jetty-alpn-server-${jetty.version}.jar

[xml]
etc/jetty-alpn.xml

[ini-template]
# tag::documentation[]
## Specifies the ordered list of application protocols supported by the server.
## The default list is specified by the list of the protocol modules that have
## been enabled, and the order is specified by the module dependencies.
# jetty.alpn.protocols=h2,http/1.1

## Specifies the protocol to use when the ALPN negotiation fails.
# jetty.alpn.defaultProtocol=http/1.1
# end::documentation[]
