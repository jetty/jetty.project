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

import com.acme.Derived;
import org.eclipse.jetty.jmx.ObjectMBean;
import org.eclipse.jetty.util.annotation.ManagedAttribute;
import org.eclipse.jetty.util.annotation.ManagedObject;
import org.eclipse.jetty.util.annotation.ManagedOperation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@ManagedObject("Derived MBean Wrapper")
public class DerivedMBean extends ObjectMBean
{
    private static final Logger LOG = LoggerFactory.getLogger(DerivedMBean.class);

    public DerivedMBean(Object managedObject)
    {
        super(managedObject);
    }

    @ManagedOperation("test of proxy operations")
    public String good()
    {
        return "not " + ((Derived)_managed).bad();
    }

    @ManagedAttribute(value = "test of proxy attributes", proxied = true)
    public String goop()
    {
        return "goop";
    }
}
