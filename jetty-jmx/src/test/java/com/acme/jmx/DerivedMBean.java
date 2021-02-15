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

package com.acme.jmx;

import com.acme.Derived;
import org.eclipse.jetty.jmx.ObjectMBean;
import org.eclipse.jetty.util.annotation.ManagedAttribute;
import org.eclipse.jetty.util.annotation.ManagedObject;
import org.eclipse.jetty.util.annotation.ManagedOperation;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

@ManagedObject("Derived MBean Wrapper")
public class DerivedMBean extends ObjectMBean
{
    private static final Logger LOG = Log.getLogger(DerivedMBean.class);

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
