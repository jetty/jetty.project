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

package org.eclipse.jetty.server.handler.jmx;

import java.time.Duration;

import org.eclipse.jetty.jmx.ObjectMBean;
import org.eclipse.jetty.server.handler.QoSHandler;
import org.eclipse.jetty.util.annotation.ManagedAttribute;

public class QoSHandlerMBean extends ObjectMBean
{
    public QoSHandlerMBean(Object managedObject)
    {
        super(managedObject);
    }

    @Override
    public QoSHandler getManagedObject()
    {
        return (QoSHandler)super.getManagedObject();
    }

    @ManagedAttribute("The maximum request suspend time in milliseconds")
    public long getMaxSuspendMillis()
    {
        return getManagedObject().getMaxSuspend().toMillis();
    }

    public void setMaxSuspendMillis(long maxSuspend)
    {
        getManagedObject().setMaxSuspend(Duration.ofMillis(maxSuspend));
    }
}
