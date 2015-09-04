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


package org.eclipse.jetty.server.session.x;

/**
 * AbstractSessionDataStore
 *
 *
 */
public abstract class AbstractSessionDataStore implements SessionDataStore
{
    
    public abstract void doStore(SessionKey key, SessionData data) throws Exception;

    /** 
     * @see org.eclipse.jetty.server.session.x.SessionDataStore#store(java.lang.String, org.eclipse.jetty.server.session.x.SessionData)
     */
    @Override
    public void store(SessionKey key, SessionData data) throws Exception
    {
        try
        {
            doStore(key, data);
        }
        finally
        {
            data.setDirty(false);
        }
    }


}
