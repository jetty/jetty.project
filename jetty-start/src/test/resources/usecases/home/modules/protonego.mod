#
# Protocol Negotiatin Selection Module
#

[depend]
protonego-impl/${protonego}

[ini-template]
# Protocol Negotiation Implementation Selection
#  choices are:
#    'npn'  : original implementation for SPDY (now deprecated)
#    'alpn' : replacement for NPN, in use by current SPDY implementations
#             and the future HTTP/2 spec
#  Note: java 1.8+ are ALPN only.
protonego=alpn
