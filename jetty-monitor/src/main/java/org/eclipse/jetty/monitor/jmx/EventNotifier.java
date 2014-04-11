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


/* ------------------------------------------------------------ */
/**
 * EventNotifier
 * 
 * Interface for classes used to send event notifications
 */
public interface EventNotifier
{

    /* ------------------------------------------------------------ */
    /**
     * This method is called when a notification event is received by the containing object
     *  
     * @param state an {@link org.eclipse.jetty.monitor.jmx.EventState event state} 
     * @param timestamp time stamp of the event
     */
    public void notify(EventTrigger trigger, EventState<?> state, long timestamp);
}
