//========================================================================
//Copyright (c) Webtide LLC
//------------------------------------------------------------------------
//All rights reserved. This program and the accompanying materials
//are made available under the terms of the Eclipse Public License v1.0
//and Apache License v2.0 which accompanies this distribution.
//
//The Eclipse Public License is available at
//http://www.eclipse.org/legal/epl-v10.html
//
//The Apache License v2.0 is available at
//http://www.apache.org/licenses/LICENSE-2.0.txt
//
//You may elect to redistribute this code under either of these licenses.
//========================================================================

package org.eclipse.jetty.server.session.jmx;

import org.eclipse.jetty.jmx.ObjectMBean;
import org.eclipse.jetty.server.session.AbstractSessionManager;

public class AbstractSessionManagerMBean extends ObjectMBean
{
    public AbstractSessionManagerMBean(Object managedObject)
    {
        super(managedObject);
    }

    /* ------------------------------------------------------------ */
    public String getObjectContextBasis()
    {
        if (_managed != null && _managed instanceof AbstractSessionManager)
        {
            AbstractSessionManager manager = (AbstractSessionManager)_managed;
            String name = manager.getContextBasis();
            if (name != null)
                return name;
        }
        return super.getObjectContextBasis();
    }
}
