package org.eclipse.jetty.test;

import javax.enterprise.inject.Produces;

public class ManifestServerID
{
    @Produces
    public ServerID getServerID()
    {
        return () ->
        {
            String implVersion = this.getClass().getPackage().getImplementationVersion();
            if(implVersion == null)
                implVersion = this.getClass().getPackage().getName();
            if(implVersion == null)
                implVersion = "unknown";
            return "CDI-Demo-" + implVersion;
        };
    }
}
