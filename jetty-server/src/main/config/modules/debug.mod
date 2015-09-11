#
# Debug module
#

[depend]
deploy

[files]
logs/

[xml]
etc/jetty-debug.xml

[ini-template]

## How many days to retain old log files
# jetty.debug.retainDays=14

## Timezone of the log entries
# jetty.debug.timezone=GMT

## Show Request/Response headers
# jetty.debug.showHeaders=true

## Rename threads while in context scope
# jetty.debug.renameThread=false

## Dump context as deployed
# jetty.debug.dumpContext=true
