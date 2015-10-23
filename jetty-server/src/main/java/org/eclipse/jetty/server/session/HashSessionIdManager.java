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

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;

import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.SessionIdManager;
import org.eclipse.jetty.server.SessionManager;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.util.ConcurrentHashSet;

/* ------------------------------------------------------------ */
/**
 * HashSessionIdManager. An in-memory implementation of the session ID manager.
 */
public class HashSessionIdManager extends AbstractSessionIdManager
{
    private final Set<String> _ids = new ConcurrentHashSet<String>();

    private Server _server;
    
    
    /* ------------------------------------------------------------ */
    public HashSessionIdManager()
    {
    }

    /* ------------------------------------------------------------ */
    public HashSessionIdManager(Random random)
    {
        super(random);
    }
    
    
    public void setServer (Server server)
    {
        _server = server;
    }

    /* ------------------------------------------------------------ */
    /**
     * @return Collection of String session IDs
     */
    public Collection<String> getSessions()
    {
        return Collections.unmodifiableCollection(_ids);
    }

  
    
    /* ------------------------------------------------------------ */
    @Override
    protected void doStart() throws Exception
    {
        if (_server == null)
            throw new IllegalStateException ("No server set on HashSessionIdManager");
        super.doStart();
    }

    /* ------------------------------------------------------------ */
    @Override
    protected void doStop() throws Exception
    {
        _ids.clear();
        super.doStop();
    }

    /* ------------------------------------------------------------ */
    /**
     * @see SessionIdManager#isIdInUse(String)
     */
    @Override
    public boolean isIdInUse(String id)
    {  
        return _ids.contains(id);
    }

    /* ------------------------------------------------------------ */
    /**
     * @see SessionIdManager#addSession(HttpSession)
     */
    @Override
    public void useId(String id)
    {
        _ids.add(id);
    }

    @Override
    public void removeId(String id)
    {
        _ids.remove(id);
    }
    

    /* ------------------------------------------------------------ */
    /**
     * @see SessionIdManager#expireAll(String)
     */
    @Override
    public void expireAll(String id)
    {
        //take the id out of the list of known sessionids for this node
        removeId(id);

        synchronized (_ids)
        {
            //tell all contexts that may have a session object with this id to
            //get rid of them
            Handler[] contexts = _server.getChildHandlersByClass(ContextHandler.class);
            for (int i=0; contexts!=null && i<contexts.length; i++)
            {
                SessionHandler sessionHandler = ((ContextHandler)contexts[i]).getChildHandlerByClass(SessionHandler.class);
                if (sessionHandler != null)
                {
                    SessionManager manager = sessionHandler.getSessionManager();

                    if (manager != null)
                       manager.expire(id);
                }
            }
        }
    }
    
    
    /* ------------------------------------------------------------ */
    @Override
    public void renewSessionId (String oldClusterId, String oldNodeId, HttpServletRequest request)
    {
        //generate a new id
        String newClusterId = newSessionId(request.hashCode());
  
        synchronized (_ids)
        {
            removeId(oldClusterId);//remove the old one from the list (and database)

            //tell all contexts to update the id 
            Handler[] contexts = _server.getChildHandlersByClass(ContextHandler.class);
            for (int i=0; contexts!=null && i<contexts.length; i++)
            {
                SessionHandler sessionHandler = ((ContextHandler)contexts[i]).getChildHandlerByClass(SessionHandler.class);
                if (sessionHandler != null) 
                {
                    SessionManager manager = sessionHandler.getSessionManager();
                    if (manager != null)
                        manager.renewSessionId(oldClusterId, oldNodeId, newClusterId, getExtendedId(newClusterId, request));
                }
            }
        }
    }

}
