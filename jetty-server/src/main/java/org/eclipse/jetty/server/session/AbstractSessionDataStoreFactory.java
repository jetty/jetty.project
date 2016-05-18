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
 * AbstractSessionDataStoreFactory
 *
 *
 */
public abstract class AbstractSessionDataStoreFactory implements SessionDataStoreFactory
{

    int _gracePeriodSec;
    
    
    
    /**
     * @return the gracePeriodSec
     */
    public int getGracePeriodSec()
    {
        return _gracePeriodSec;
    }


    /**
     * @param gracePeriodSec the gracePeriodSec to set
     */
    public void setGracePeriodSec(int gracePeriodSec)
    {
        _gracePeriodSec = gracePeriodSec;
    }
   

}
