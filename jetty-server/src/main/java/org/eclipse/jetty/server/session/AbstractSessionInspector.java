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


package org.eclipse.jetty.server.session;

/**
 * AbstractInspector
 */
public abstract class AbstractSessionInspector implements SessionInspector
{
    /**
     * <ul>
     *     <li>&lt;0 means never inspect</li>
     *     <li>0 means always inspect</li>
     *     <li>&gt;0 means inspect at that interval</li>
     * </ul>
     */
    protected int _timeoutSec = -1;
    
    /**
     * -ve means never inspect
     * 0 means always inspect
     * +ve means inspect at interval
     */
    protected long _lastTime;
    
    /** 
     * @see org.eclipse.jetty.server.session.SessionInspector#getTimeoutSec()
     */
    @Override
    public int getTimeoutSec()
    {
       return _timeoutSec;
    }

    /** 
     * @see org.eclipse.jetty.server.session.SessionInspector#setTimeoutSet(int)
     */
    @Override
    public void setTimeoutSet(int sec)
    {
        _timeoutSec = sec;
    }

    /** 
     * @see org.eclipse.jetty.server.session.SessionInspector#preInspection()
     */
    @Override
    public boolean preInspection()
    {
        long now = System.currentTimeMillis();
        return checkTimeout(now);
    }

    /** 
     * @see org.eclipse.jetty.server.session.SessionInspector#inspect(org.eclipse.jetty.server.session.Session)
     */
    @Override
    public void inspect(Session s)
    {
        _lastTime = System.currentTimeMillis();
    }

    /** 
     * @see org.eclipse.jetty.server.session.SessionInspector#postInspection()
     */
    @Override
    public void postInspection()
    {

    }

    protected boolean checkTimeout (long now)
    {
        if (_timeoutSec == 0)
            return true; // always inspect
        
        if (_timeoutSec < 0)
            return false;  //never inspect
        
        if (_lastTime == 0)
            return true;  //always perform inspection on first use
        
        
        return ((now - _lastTime)*1000L) >= _timeoutSec; //only inspect if interval since last inspection has expired
    }
}
