#
# Debug module
#

[depend]
server

[files]
logs/

[xml]
etc/jetty-debuglog.xml

[ini-template]
## Logging directory (relative to $jetty.base)
# jetty.debuglog.dir=logs

## Whether to append to existing file
# jetty.debuglog.append=false

## How many days to retain old log files
# jetty.debuglog.retainDays=90

## Timezone of the log entries
# jetty.debuglog.timezone=GMT
