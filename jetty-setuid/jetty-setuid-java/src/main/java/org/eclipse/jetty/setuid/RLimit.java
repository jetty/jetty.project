//
//  ========================================================================
//  Copyright (c) 1995-2012 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.setuid;

public class RLimit
{
    int _soft;
    int _hard;

    
    public int getSoft ()
    {
        return _soft;
    }
    
    public void setSoft (int soft)
    {
        _soft = soft;
    }
    
    public int getHard ()
    {
        return _hard;
    }
    
    public void setHard (int hard)
    {
        _hard = hard;
    }
    
    public String toString()
    {
        return "rlimit_nofiles (soft="+_soft+", hard="+_hard+")";
    }
    
}
