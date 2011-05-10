// ========================================================================
// Copyright (c) 2004-2009 Mort Bay Consulting Pty. Ltd.
// ------------------------------------------------------------------------
// All rights reserved. This program and the accompanying materials
// are made available under the terms of the Eclipse Public License v1.0
// and Apache License v2.0 which accompanies this distribution.
// The Eclipse Public License is available at 
// http://www.eclipse.org/legal/epl-v10.html
// The Apache License v2.0 is available at
// http://www.opensource.org/licenses/apache2.0.php
// You may elect to redistribute this code under either of these licenses. 
// ========================================================================

package org.eclipse.jetty.webapp.jmx;

import org.eclipse.jetty.server.handler.jmx.ContextHandlerMBean;
import org.eclipse.jetty.webapp.WebAppContext;

public class WebAppContextMBean extends ContextHandlerMBean
{

    public WebAppContextMBean(Object managedObject)
    {
        super(managedObject);
    }

    /* ------------------------------------------------------------ */
    public String getObjectNameBasis()
    {
        String basis = super.getObjectNameBasis();
        if (basis!=null)
            return basis;
        
        if (_managed!=null && _managed instanceof WebAppContext)
        {
            WebAppContext context = (WebAppContext)_managed;
            String name = context.getWar();
            if (name!=null)
                return name;
        }
        return null;
    }
}
