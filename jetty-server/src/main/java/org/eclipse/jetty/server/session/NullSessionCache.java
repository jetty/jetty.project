//
//  ========================================================================
//  Copyright (c) 1995-2019 Mort Bay Consulting Pty. Ltd.
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

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSessionAttributeListener;
import javax.servlet.http.HttpSessionBindingEvent;


/**
 * NullSessionCache
 *
 * Does not actually cache any Session objects. Useful for testing.
 * Also useful if you do not want to share Session objects with the same id between
 * simultaneous requests: note that this means that context forwarding can't share
 * the same id either.
 */
public class NullSessionCache extends AbstractSessionCache
{
    /**
     * If the writethrough mode is ALWAYS or NEW, then use an
     * attribute listener to ascertain when the attribute has changed.
     *
     */
    public class WriteThroughAttributeListener implements HttpSessionAttributeListener
    {
        Set<Session> _sessionsBeingWritten = ConcurrentHashMap.newKeySet();
        
        @Override
        public void attributeAdded(HttpSessionBindingEvent event)
        {   
            doAttributeChanged(event);
        }

        @Override
        public void attributeRemoved(HttpSessionBindingEvent event)
        {
            doAttributeChanged(event);
        }

        @Override
        public void attributeReplaced(HttpSessionBindingEvent event)
        {
            doAttributeChanged(event);
        }

        private void doAttributeChanged(HttpSessionBindingEvent event)
        {
            if (_writeThroughMode == WriteThroughMode.ON_EXIT)
                return;

            Session session = (Session)event.getSession();
            
            SessionDataStore store = getSessionDataStore();
            
            if (store == null)
                return;

            if (_writeThroughMode == WriteThroughMode.ALWAYS
                || (_writeThroughMode == WriteThroughMode.NEW && session.isNew()))
            {
                //ensure that a call to willPassivate doesn't result in a passivation
                //listener removing an attribute, which would cause this listener to
                //be called again
                if (_sessionsBeingWritten.add(session))
                {
                    try
                    {
                        //should hold the lock on the session, but as sessions are never shared
                        //with the NullSessionCache, there can be no other thread modifying the 
                        //same session at the same time (although of course there can be another
                        //request modifying its copy of the session data, so it is impossible
                        //to guarantee the order of writes).
                        if (store.isPassivating())
                            session.willPassivate();
                        store.store(session.getId(), session.getSessionData());
                        if (store.isPassivating())
                            session.didActivate();
                    }
                    catch (Exception e)
                    {
                        LOG.warn("Write through of {} failed", e);
                    }
                    finally
                    {
                        _sessionsBeingWritten.remove(session);
                    }
                }
            }
        }
    }

    /**
     * Defines the circumstances a session will be written to the backing store. 
     */
    public enum WriteThroughMode
    {
        /**
         * ALWAYS means write through every attribute change.
         */
        ALWAYS,
        /**
         * NEW means to write through every attribute change only 
         * while the session is freshly created, ie its id has not yet been returned to the client
         */
        NEW,
        /**
         * ON_EXIT means write the session only when the request exits 
         * (which is the default behaviour of AbstractSessionCache)
         */
        ON_EXIT
    };
    
    private WriteThroughMode _writeThroughMode = WriteThroughMode.ON_EXIT;
    protected WriteThroughAttributeListener _listener = null;
    

    /**
     * @return the writeThroughMode
     */
    public WriteThroughMode getWriteThroughMode()
    {
        return _writeThroughMode;
    }


    /**
     * @param writeThroughMode the writeThroughMode to set
     */
    public void setWriteThroughMode(WriteThroughMode writeThroughMode)
    {
        if (getSessionHandler() == null)
            throw new IllegalStateException ("No SessionHandler");
        
        //assume setting null is the same as ON_EXIT
        if (writeThroughMode == null)
        {
            if (_listener != null)
                getSessionHandler().removeEventListener(_listener);
            _listener = null;
            _writeThroughMode = WriteThroughMode.ON_EXIT;
            return;
        }
        
        switch (writeThroughMode)
        {
            case ON_EXIT:
            {
                if (_listener != null)
                    getSessionHandler().removeEventListener(_listener);
                _listener = null;
                break;
            }
            case NEW:
            case ALWAYS:
            {
                if (_listener == null)
                {
                    _listener = new WriteThroughAttributeListener();
                    getSessionHandler().addEventListener(_listener);
                }
                break;
            }
        }
        _writeThroughMode = writeThroughMode;
    }


    /**
     * @param handler The SessionHandler related to this SessionCache
     */
    public NullSessionCache(SessionHandler handler)
    {
        super(handler);
        super.setEvictionPolicy(EVICT_ON_SESSION_EXIT);
    }
    

    /**
     * @see org.eclipse.jetty.server.session.SessionCache#shutdown()
     */
    @Override
    public void shutdown()
    {
    }

    /**
     * @see org.eclipse.jetty.server.session.AbstractSessionCache#newSession(org.eclipse.jetty.server.session.SessionData)
     */
    @Override
    public Session newSession(SessionData data)
    {
        return new Session(getSessionHandler(), data);
    }

    /**
     * @see org.eclipse.jetty.server.session.AbstractSessionCache#newSession(javax.servlet.http.HttpServletRequest, org.eclipse.jetty.server.session.SessionData)
     */
    @Override
    public Session newSession(HttpServletRequest request, SessionData data)
    {
        return new Session(getSessionHandler(), request, data);
    }

    /**
     * @see org.eclipse.jetty.server.session.AbstractSessionCache#doGet(java.lang.String)
     */
    @Override
    public Session doGet(String id)
    {
        //do not cache anything
        return null;
    }

    /**
     * @see org.eclipse.jetty.server.session.AbstractSessionCache#doPutIfAbsent(java.lang.String, org.eclipse.jetty.server.session.Session)
     */
    @Override
    public Session doPutIfAbsent(String id, Session session)
    {
        //nothing was stored previously
        return null;
    }

    /**
     * @see org.eclipse.jetty.server.session.AbstractSessionCache#doReplace(java.lang.String, org.eclipse.jetty.server.session.Session, org.eclipse.jetty.server.session.Session)
     */
    @Override
    public boolean doReplace(String id, Session oldValue, Session newValue)
    {
        //always accept new value
        return true;
    }

    /**
     * @see org.eclipse.jetty.server.session.AbstractSessionCache#doDelete(java.lang.String)
     */
    @Override
    public Session doDelete(String id)
    {
        return null;
    }

    /**
     * @see org.eclipse.jetty.server.session.AbstractSessionCache#setEvictionPolicy(int)
     */
    @Override
    public void setEvictionPolicy(int evictionTimeout)
    {
        LOG.warn("Ignoring eviction setting:" + evictionTimeout);
    }
}
