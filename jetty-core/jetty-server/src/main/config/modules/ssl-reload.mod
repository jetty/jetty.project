[description]
Enables the KeyStore to be reloaded when the KeyStore file changes.

[tags]
connector
ssl

[depend]
ssl

[xml]
etc/jetty-ssl-context-reload.xml

[ini-template]
# tag::documentation[]
# Monitored directory scan period, in seconds.
# jetty.sslContext.reload.scanInterval=1
# end::documentation[]
