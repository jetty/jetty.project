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

import java.io.IOException;

import javax.servlet.http.HttpServletRequest;

import org.eclipse.jetty.http.Parser;

public class NestedParser implements Parser
{

    public NestedParser()
    {
    }

    public void reset()
    {
    }

    public void returnBuffers()
    {
    }

    public boolean isComplete()
    {
        return false;
    }

    public boolean parseAvailable() throws IOException
    {
        return false;
    }

    public boolean isMoreInBuffer() throws IOException
    {
        return false;
    }

    public boolean isIdle()
    {
        return false;
    }

    public boolean isPersistent()
    {
        return false;
    }

    public void setPersistent(boolean persistent)
    {
    }

}
