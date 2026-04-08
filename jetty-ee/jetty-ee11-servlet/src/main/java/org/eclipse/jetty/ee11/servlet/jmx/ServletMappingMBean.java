//
// ========================================================================
// Copyright (c) 1995 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.ee11.servlet.jmx;

import org.eclipse.jetty.ee11.servlet.ServletMapping;
import org.eclipse.jetty.jmx.ObjectMBean;

public class ServletMappingMBean extends ObjectMBean
{
    public ServletMappingMBean(Object managedObject)
    {
        super(managedObject);
    }

    @Override
    public ServletMapping getManagedObject()
    {
        return (ServletMapping)super.getManagedObject();
    }

    @Override
    public String getObjectNameBasis()
    {
        return getManagedObject().getServletName();
    }
}
