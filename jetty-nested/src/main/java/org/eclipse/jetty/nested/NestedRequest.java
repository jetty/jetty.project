//
//  ========================================================================
//  Copyright (c) 1995-2013 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.nested;

import javax.servlet.http.HttpServletRequest;

import org.eclipse.jetty.http.HttpSchemes;
import org.eclipse.jetty.server.Request;

public class NestedRequest extends Request
{
    private final HttpServletRequest _outer;
    
    public NestedRequest(HttpServletRequest outer)
    {
        _outer=outer;
    }
    
    void setConnection(NestedConnection connection)
    {
        super.setConnection(connection);
    }

    public boolean isSecure()
    {
        return _outer.isSecure() || HttpSchemes.HTTPS.equals(getScheme());
    }
    
}
