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

package com.acme.jmx;

import com.acme.Managed;
import org.eclipse.jetty.jmx.ObjectMBean;
import org.eclipse.jetty.util.annotation.ManagedAttribute;
import org.eclipse.jetty.util.annotation.ManagedObject;
import org.eclipse.jetty.util.annotation.ManagedOperation;

@ManagedObject("Managed MBean Wrapper")
public class ManagedMBean extends ObjectMBean
{
    public ManagedMBean(Object managedObject)
    {
        super(managedObject);
    }

    @ManagedOperation("test of proxy operations")
    public String good()
    {
        return "not managed " + ((Managed)_managed).bad();
    }

    @ManagedAttribute(value = "test of proxy attributes", proxied = true)
    public String goop()
    {
        return "goop";
    }
}
