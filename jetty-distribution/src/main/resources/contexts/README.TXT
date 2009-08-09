
This directory is scanned by the ContextDeployer instance
configured by the standard $JETTY_HOME/etc/jetty.xml configuration. 

It should contain XmlConfiguration files that describe individual
contexts to be deployed to the server.  This directory is scanned
for additions, removals and updates for hot deployment.

Frequenty the context configuration files here will reference
war files or directories from $JETTY_HOME/webapps.  Care must be
taken to avoid a WebAppDeployer deploying duplicates of such
webapplications.

The directory ../contexts-available contains more example contexts
that may be deployed by being copied here.
