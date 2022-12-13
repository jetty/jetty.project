[description]
Enables authentication and authorization for remote clients
that want to connect to JMX to access the platform MBeans,
via configuration files for user passwords and user roles.

[depend]
jmx-remote

[files]
basehome:modules/jmx.d/jmxremote.password|etc/jmxremote.password
basehome:modules/jmx.d/jmxremote.access|etc/jmxremote.access
basehome:modules/jmx.d/jmx-remote-auth.xml|etc/jmx-remote-auth.xml

[xml]
etc/jmx-remote-auth.xml
