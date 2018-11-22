DO NOT EDIT - See: https://www.eclipse.org/jetty/documentation/current/startup-modules.html

[description]
Enables a format string style request log.

[provides]
requestlog

[tags]
customrequestlog

[depend]
server

[xml]
etc/jetty-customrequestlog.xml

[files]
logs/

[ini-template]
## Logging directory (relative to $jetty.base)
# jetty.customrequestlog.dir=logs

## File path
# jetty.customrequestlog.filePath=${jetty.customrequestlog.dir}/yyyy_mm_dd.request.log

## Date format for rollovered files (uses SimpleDateFormat syntax)
# jetty.customrequestlog.filenameDateFormat=yyyy_MM_dd

## How many days to retain old log files
# jetty.customrequestlog.retainDays=90

## Whether to append to existing file
# jetty.customrequestlog.append=false

## Timezone of the log entries
# jetty.customrequestlog.timezone=GMT

## Format string
# jetty.customrequestlog.formatString=%a - %u %t "%r" %s %B "%{Referer}i" "%{User-Agent}i" "%C"
