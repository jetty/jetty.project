#
# Example of providing a demo configuration, using a ${jetty.base}
#
# Additional ini files are in demo-base/start.d
# 

[depends]
rewrite
jaas
test-keystore


[xml]
# Enable rewrite examples
etc/demo-rewrite-rules.xml

# Add the test realm
etc/test-realm.xml

[ini-template]
# Enable security via jaas, and configure it
jetty.jaas.login.conf=etc/login.conf

# Websocket chat examples needs websocket enabled
# Don't start for all contexts (set to true in test.xml context)
org.eclipse.jetty.websocket.jsr356=false

# Create and configure the test realm
jetty.demo.realm=etc/realm.properties

# JDBC needed by test-jndi and test-spec
--module=jdbc
