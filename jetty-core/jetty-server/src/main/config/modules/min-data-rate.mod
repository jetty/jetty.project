[description]
Enables minimum data rate enforcement for request content and response content.

[tags]
server

[depends]
server

[xml]
etc/jetty-min-data-rate.xml

[ini-template]
#tag::documentation[]
## The minimum data rate to enforce when reading request content, in bytes/s.
## Use 0 to disable the minimum data rate enforcement.
#jetty.minDataRate.minReadRate=0

## The minimum data rate to enforce when writing response content, in bytes/s.
## Use 0 to disable the minimum data rate enforcement.
#jetty.minDataRate.minWriteRate=0
#end::documentation[]
