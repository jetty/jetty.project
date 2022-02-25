//
// ========================================================================
// Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
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
