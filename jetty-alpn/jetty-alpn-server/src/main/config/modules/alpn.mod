[description]
Enables the ALPN (Application Layer Protocol Negotiation) TLS extension.

[depend]
ssl
alpn-impl/alpn-${java.version.platform}

[lib]
lib/jetty-alpn-client-${jetty.version}.jar
lib/jetty-alpn-server-${jetty.version}.jar

[xml]
etc/jetty-alpn.xml

[ini-template]
## Overrides the order protocols are chosen by the server.
## The default order is that specified by the order of the
## modules declared in start.ini.
# jetty.alpn.protocols=h2,http/1.1

## Specifies what protocol to use when negotiation fails.
# jetty.alpn.defaultProtocol=http/1.1

## ALPN debug logging on System.err
# jetty.alpn.debug=false

