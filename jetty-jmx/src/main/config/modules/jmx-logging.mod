DO NOT EDIT - See: https://www.eclipse.org/jetty/documentation/current/startup-modules.html

[description]
Enables logging of JMX invocations.

[depend]
jmx

[xml]
etc/jetty-jmx-logging.xml

[ini-template]
## The logger name used to log JMX invocations.
## When null, the class name of ObjectMBean.LoggingListener is used.
# jetty.jmx.logging.loggerName
