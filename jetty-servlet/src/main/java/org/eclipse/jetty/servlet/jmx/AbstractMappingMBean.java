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

package org.eclipse.jetty.servlet.jmx;

import org.eclipse.jetty.jmx.ObjectMBean;
import org.eclipse.jetty.servlet.AbstractMapping;

public class AbstractMappingMBean extends ObjectMBean
{

    public AbstractMappingMBean(Object managedObject)
    {
        super(managedObject);
    }

    public String getObjectContextBasis()
    {
        if (_managed != null && _managed instanceof AbstractMapping)
        {
            AbstractMapping mapping = (AbstractMapping)_managed;
            String name = mapping.getContextBasis();
            if (name != null)
                return name;
        }
        
        return super.getObjectContextBasis();
    }

    public String getObjectNameBasis()
    {
        if (_managed != null && _managed instanceof AbstractMapping)
        {
            AbstractMapping mapping = (AbstractMapping)_managed;
            String name = mapping.getEntityName();
            if (name != null)
                return name;
        }
        
        return super.getObjectNameBasis();
    }
}
