//
//  ========================================================================
//  Copyright (c) 1995-2015 Mort Bay Consulting Pty. Ltd.
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


package org.eclipse.jetty.server.session;

import org.eclipse.jetty.server.handler.ContextHandler.Context;
import org.eclipse.jetty.util.component.AbstractLifeCycle;

/**
 * AbstractSessionDataStore
 *
 *
 */
public abstract class AbstractSessionDataStore extends AbstractLifeCycle implements SessionDataStore
{
    protected Context _context; //context associated with this session data store
    protected String _node; //the unique id of the node on which this context is deployed
    
    public String getNode()
    {
        return _node;
    }


    public void setNode(String node)
    {
        _node = node;
    }


    public abstract void doStore(SessionKey key, SessionData data, boolean isNew) throws Exception;

    
    public Context getContext()
    {
        return _context;
    }


    public void setContext(Context context)
    {
        _context = context;
    }


    /** 
     * @see org.eclipse.jetty.server.session.SessionDataStore#store(java.lang.String, org.eclipse.jetty.server.session.SessionData)
     */
    @Override
    public void store(SessionKey key, SessionData data) throws Exception
    {
        long lastSave = data.getLastSaved();
        
        data.setLastSaved(System.currentTimeMillis());
        try
        {
            doStore(key, data, (lastSave<=0));
        }
        catch (Exception e)
        {
            //reset last save time
            data.setLastSaved(lastSave);
        }
        finally
        {
            data.setDirty(false);
        }
    }
}
