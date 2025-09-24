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

package org.eclipse.jetty.server.jmx;

import org.eclipse.jetty.jmx.ObjectMBean;
import org.eclipse.jetty.util.annotation.ManagedObject;

public class Handler
{
    @ManagedObject
    public static class AbstractMBean extends ObjectMBean
    {
        public AbstractMBean(Object managedObject)
        {
            super(managedObject);
        }

        @Override
        public org.eclipse.jetty.server.Handler.Abstract getManagedObject()
        {
            return (org.eclipse.jetty.server.Handler.Abstract)super.getManagedObject();
        }
    }
}
