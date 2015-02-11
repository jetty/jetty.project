#
# Protocol Negotiatin Selection Module
#

[depend]
protonego-impl/${protonego}

[ini-template]
# Protocol Negotiation Implementation Selection
#  choices are:
#    'alpn' : in use by current HTTP/2 implementation.
#  Note: java 1.8+ are ALPN only.
protonego=alpn

# Configuration for ALPN
# alpn.protocols=h2-14,http/1.1
# alpn.defaultProtocol=http/1.1
