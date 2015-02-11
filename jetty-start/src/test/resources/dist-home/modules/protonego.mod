#
# Protocol Negotiatin Selection Module
#

[depend]
protonego-impl/${protonego}

[ini-template]
# Protocol Negotiation Implementation Selection
# Always set to 'alpn'
protonego=alpn

# Configuration for ALPN
# alpn.protocols=h2-14,http/1.1
# alpn.defaultProtocol=http/1.1

