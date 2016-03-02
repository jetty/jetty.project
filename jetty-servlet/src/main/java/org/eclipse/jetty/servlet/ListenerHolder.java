//
//  ========================================================================
//  Copyright (c) 1995-2016 Mort Bay Consulting Pty. Ltd.
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


package org.eclipse.jetty.servlet;

import java.util.EventListener;

/**
 * ListenerHolder
 *
 * Specialization of AbstractHolder for servlet listeners. This
 * allows us to record where the listener originated - web.xml,
 * annotation, api etc.
 */
public class ListenerHolder extends BaseHolder<EventListener>
{
    private EventListener _listener;
    

    public ListenerHolder(Source source)
    {
        super(source);
    }
   
    
    public void setListener(EventListener listener)
    {
        _listener = listener;
        setClassName(listener.getClass().getName());
        setHeldClass(listener.getClass());
        _extInstance=true;
    }

    public EventListener getListener()
    {
        return _listener;
    }


    @Override
    public void doStart() throws Exception
    {
        //Listeners always have an instance eagerly created, it cannot be deferred to the doStart method
        if (_listener == null)
            throw new IllegalStateException("No listener instance");
        
        super.doStart();
    }
}
