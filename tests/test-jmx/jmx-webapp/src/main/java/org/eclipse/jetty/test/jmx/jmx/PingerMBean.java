//
//  ========================================================================
//  Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
//  ------------------------------------------------------------------------
//  All rights reserved. This program and the accompanying materials
//  are made available under the terms of the Eclipse Public License v1.0
//  and Apache License v2.0 which accompanies this distribution.
//
//      The Eclipse Public License is available at
//      http://www.eclipse.org/legal/epl-v10.html
//
//      The Apache License v2.0 is available at
//      http://www.opensource.org/licenses/apache2.0.php
//
//  You may elect to redistribute this code under either of these licenses.
//  ========================================================================
//

package org.eclipse.jetty.test.jmx.jmx;

import org.eclipse.jetty.jmx.ObjectMBean;
import org.eclipse.jetty.test.jmx.Pinger;
import org.eclipse.jetty.util.annotation.ManagedAttribute;
import org.eclipse.jetty.util.annotation.ManagedObject;
import org.eclipse.jetty.util.annotation.ManagedOperation;

@ManagedObject("Pinger facility")
public class PingerMBean extends ObjectMBean
{
    public PingerMBean(Object managedObject)
    {
        super(managedObject);
    }

    private Pinger getPinger()
    {
        return (Pinger)getManagedObject();
    }

    @ManagedOperation("Issue Ping")
    public String ping()
    {
        return getPinger().ping();
    }

    @ManagedAttribute("Count of pings")
    public int getCount()
    {
        return getPinger().getCount();
    }
}
