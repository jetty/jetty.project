//
//  ========================================================================
//  Copyright (c) 1995-2014 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.monitor.jmx;

import java.security.InvalidParameterException;



/* ------------------------------------------------------------ */
/**
 */
public class SimpleAction extends MonitorAction
{
    public SimpleAction(EventTrigger trigger, EventNotifier notifier, long pollInterval)
        throws InvalidParameterException
    {
        super(trigger,notifier,pollInterval);
    }

    /* ------------------------------------------------------------ */
    /**
     * @see org.eclipse.jetty.monitor.jmx.MonitorAction#execute(org.eclipse.jetty.monitor.jmx.EventTrigger, org.eclipse.jetty.monitor.jmx.EventState, long)
     */

    @Override
    public void execute(EventTrigger trigger, EventState<?> state, long timestamp)
    {
        System.out.printf("Action time: %tc%n", timestamp);
    }
}
