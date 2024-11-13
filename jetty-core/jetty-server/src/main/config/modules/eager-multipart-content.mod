
[description]
Applies MultiPart configuration to the EagerContentHandler

[tags]
server

[before]
eager-content

[xml]
etc/jetty-eager-multipart-content.xml

[ini-template]
#tag::documentation[]
## the directory where parts will be saved as files.
# jetty.eager.multipart.location=/tmp

## The maximum number of parts that can be parsed from the multipart content, or -1 for unlimited.
# jetty.eager.multipart.maxParts=

## The maximum size in bytes of the whole multipart content, or -1 for unlimited.
# jetty.eager.multipart.maxSize=

## The maximum part size in bytes, or -1 for unlimited.
# jetty.eager.multipart.maxPartSize=

## The maximum size of a part in memory, after which it will be written as a file.
# jetty.eager.multipart.maxMemoryPartSize=

## The max length of a Part header, in bytes, or -1 for unlimited length.
# jetty.eager.multipart.maxHeadersSize=

## Whether parts without a fileName are stored as files.
# jetty.eager.multipart.useFilesForPartsWithoutFileName=

## The MultiPart compliance mode.
# jetty.eager.multipart.complianceMode=RFC7578
#end::documentation[]