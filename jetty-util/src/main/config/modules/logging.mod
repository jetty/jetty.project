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
# Configure jetty logging for default internal behavior STDERR output
# -Dorg.eclipse.jetty.util.log.class=org.eclipse.jetty.util.log.StdErrLog

# Configure jetty logging for slf4j
# -Dorg.eclipse.jetty.util.log.class=org.eclipse.jetty.util.log.Slf4jLog

# Configure jetty logging for java.util.logging
# -Dorg.eclipse.jetty.util.log.class=org.eclipse.jetty.util.log.JavaUtilLog

# STDERR / STDOUT Logging
# Number of days to retain logs
# jetty.log.retain=90
# Directory for logging output
# Either a path relative to ${jetty.base} or an absolute path
# jetty.logs=logs
