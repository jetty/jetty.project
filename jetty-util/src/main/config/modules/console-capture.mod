# DO NOT EDIT - See: https://www.eclipse.org/jetty/documentation/

[description]
Redirects the JVM console stderr and stdout to a rolling log file.

[tags]
logging

[depends]
logging

[xml]
etc/console-capture.xml

[files]
logs/

[ini-template]
# tag::documentation[]
## Logging directory (relative to $JETTY_BASE).
# jetty.console-capture.dir=./logs

## Whether to append to existing file.
# jetty.console-capture.append=true

## How many days to retain old log files.
# jetty.console-capture.retainDays=90

## Timezone ID of the log timestamps, as specified by java.time.ZoneId.
# jetty.console-capture.timezone=GMT
# end::documentation[]
