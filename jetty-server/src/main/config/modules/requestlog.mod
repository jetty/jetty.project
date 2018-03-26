DO NOT EDIT - See: https://www.eclipse.org/jetty/documentation/current/startup-modules.html

[description]
Enables a NCSA style request log.

[tags]
requestlog

[depend]
server

[xml]
etc/jetty-requestlog.xml

[files]
logs/

[ini-template]
## Logging directory (relative to $jetty.base)
# jetty.requestlog.dir=logs

## File path
# jetty.requestlog.filePath=${jetty.requestlog.dir}/yyyy_mm_dd.request.log

## Date format for rollovered files (uses SimpleDateFormat syntax)
# jetty.requestlog.filenameDateFormat=yyyy_MM_dd

## How many days to retain old log files
# jetty.requestlog.retainDays=90

## Whether to append to existing file
# jetty.requestlog.append=false

## Whether to use the extended log output
# jetty.requestlog.extended=true

## Whether to log http cookie information
# jetty.requestlog.cookies=true

## Timezone of the log entries
# jetty.requestlog.timezone=GMT

## Whether to log LogLatency
# jetty.requestlog.loglatency=false
