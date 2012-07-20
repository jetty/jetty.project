package org.eclipse.jetty.servlet.api;
//========================================================================
//Copyright 2011-2012 Mort Bay Consulting Pty. Ltd.
//------------------------------------------------------------------------
//All rights reserved. This program and the accompanying materials
//are made available under the terms of the Eclipse Public License v1.0
//and Apache License v2.0 which accompanies this distribution.
//The Eclipse Public License is available at
//http://www.eclipse.org/legal/epl-v10.html
//The Apache License v2.0 is available at
//http://www.opensource.org/licenses/apache2.0.php
//You may elect to redistribute this code under either of these licenses.
//========================================================================

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
