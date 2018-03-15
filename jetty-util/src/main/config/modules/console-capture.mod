DO NOT EDIT - See: https://www.eclipse.org/jetty/documentation/current/startup-modules.html

[description]
Redirects JVMs console stderr and stdout to a log file,
including output from Jetty's default StdErrLog logging.

[tags]
logging

[xml]
etc/console-capture.xml

[files]
logs/

[lib]
resources/

[ini-template]
## Logging directory (relative to $jetty.base)
# jetty.console-capture.dir=logs

## Whether to append to existing file
# jetty.console-capture.append=true

## How many days to retain old log files
# jetty.console-capture.retainDays=90

## Timezone of the log timestamps
# jetty.console-capture.timezone=GMT
