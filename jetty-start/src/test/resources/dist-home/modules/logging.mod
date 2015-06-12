#
# Jetty std err/out logging
#

[xml]
etc/jetty-logging.xml

[files]
logs/

[lib]
lib/logging/**.jar
resources/

[ini-template]
## Logging Configuration
## Configure jetty logging for default internal behavior STDERR output
# -Dorg.eclipse.jetty.util.log.class=org.eclipse.jetty.util.log.StdErrLog

## Configure jetty logging for slf4j
# -Dorg.eclipse.jetty.util.log.class=org.eclipse.jetty.util.log.Slf4jLog

## Configure jetty logging for java.util.logging
# -Dorg.eclipse.jetty.util.log.class=org.eclipse.jetty.util.log.JavaUtilLog

## Logging directory (relative to $jetty.base)
# jetty.logging.dir=logs

## Whether to append to existing file
# jetty.logging.append=false

## How many days to retain old log files
# jetty.logging.retainDays=90

## Timezone of the log timestamps
# jetty.logging.timezone=GMT
