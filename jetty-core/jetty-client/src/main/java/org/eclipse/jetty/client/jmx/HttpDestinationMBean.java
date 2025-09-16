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

package org.eclipse.jetty.client.jmx;

import org.eclipse.jetty.client.Origin;
import org.eclipse.jetty.client.transport.HttpDestination;
import org.eclipse.jetty.jmx.ObjectMBean;
import org.eclipse.jetty.util.annotation.ManagedAttribute;
import org.eclipse.jetty.util.annotation.ManagedObject;

@ManagedObject("HttpClient destination")
public class HttpDestinationMBean extends ObjectMBean
{
    public HttpDestinationMBean(Object managedObject)
    {
        super(managedObject);
    }

    @Override
    public HttpDestination getManagedObject()
    {
        return (HttpDestination)super.getManagedObject();
    }

    @Override
    public String getObjectContextBasis()
    {
        return getManagedObject().getOrigin().asString();
    }

    @ManagedAttribute("The destination origin")
    public String getOrigin()
    {
        Origin origin = getManagedObject().getOrigin();
        return origin.toString();
    }
}
