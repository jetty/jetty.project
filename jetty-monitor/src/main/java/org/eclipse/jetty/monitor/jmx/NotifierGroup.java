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

import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;



/* ------------------------------------------------------------ */
/**
 * NotifierGroup
 * 
 * This class allows for grouping of the event notifiers 
 */
public class NotifierGroup implements EventNotifier
{
    private Set<EventNotifier> _group;

    /* ------------------------------------------------------------ */
    /**
     * Create a notifier group
     */
    public NotifierGroup()
    {
        _group = new HashSet<EventNotifier>();
    }
    
    /* ------------------------------------------------------------ */
    /**
     * Retrieve all event notifier associated with this group
     * 
     * @return collection of event notifiers
     */    
    public Collection<EventNotifier> getNotifiers()
    {
        return Collections.unmodifiableSet(_group);
    }

    /* ------------------------------------------------------------ */
    /**
     * Add specified event notifier to event notifier group
     * 
     * @param notifier event notifier to be added
     * @return true if successful
     */
    public boolean addNotifier(EventNotifier notifier)
    {
        return _group.add(notifier);
    }
    
    /* ------------------------------------------------------------ */
    /**
     * Add a collection of event notifiers to event notifier group
     * 
     * @param notifiers collection of event notifiers to be added
     * @return true if successful
     */
    public boolean addNotifiers(Collection<EventNotifier> notifiers)
    {
        return _group.addAll(notifiers);
    }
    
    /* ------------------------------------------------------------ */
    /**
     * Remove event notifier from event notifier group
     * 
     * @param notifier event notifier to be removed
     * @return true if successful
     */
    public boolean removeNotifier(EventNotifier notifier)
    {
        return _group.remove(notifier);
    }
 
    /* ------------------------------------------------------------ */
    /**
     * Remove a collection of event notifiers from event notifier group
     * 
     * @param notifiers collection of event notifiers to be removed
     * @return true if successful
     */
    public boolean removeNotifiers(Collection<EventNotifier> notifiers)
    {
        return _group.removeAll(notifiers);
    }

    /* ------------------------------------------------------------ */
    /**
     * Invoke the notify() method of each of the notifiers in group
     * 
     * @see org.eclipse.jetty.monitor.jmx.EventNotifier#notify(org.eclipse.jetty.monitor.jmx.EventTrigger, org.eclipse.jetty.monitor.jmx.EventState, long)
     */
    public void notify(EventTrigger trigger, EventState<?> state, long timestamp)
    {
        for (EventNotifier notifier: _group)
        {
            notifier.notify(trigger, state, timestamp);
        }
    }
}
