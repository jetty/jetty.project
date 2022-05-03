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

package org.eclipse.jetty.ee10.servlet.jmx;

import org.eclipse.jetty.ee10.servlet.Holder;
import org.eclipse.jetty.jmx.ObjectMBean;

public class HolderMBean extends ObjectMBean
{
    public HolderMBean(Object managedObject)
    {
        super(managedObject);
    }

    @Override
    public String getObjectNameBasis()
    {
        if (_managed != null && _managed instanceof Holder)
        {
            Holder holder = (Holder)_managed;
            String name = holder.getName();
            if (name != null)
                return name;
        }
        return super.getObjectNameBasis();
    }
}
