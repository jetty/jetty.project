package org.eclipse.jetty.servlet.api;

import java.util.Collection;
import java.util.Set;

public interface ServletRegistration
{
    public Set<String> addMapping(String... urlPatterns);
   
    public Collection<String> getMappings();

    public String getRunAsRole();

    interface Dynamic extends ServletRegistration, Registration.Dynamic 
    {
        public void setLoadOnStartup(int loadOnStartup);

        public void setRunAsRole(String roleName);
    }

}
