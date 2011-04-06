// ========================================================================
// Copyright (c) 2010-2011 Mort Bay Consulting Pty. Ltd.
// ------------------------------------------------------------------------
// All rights reserved. This program and the accompanying materials
// are made available under the terms of the Eclipse Public License v1.0
// and Apache License v2.0 which accompanies this distribution.
// The Eclipse Public License is available at 
// http://www.eclipse.org/legal/epl-v10.html
// The Apache License v2.0 is available at
// http://www.opensource.org/licenses/apache2.0.php
// You may elect to redistribute this code under either of these licenses. 
// ========================================================================
package org.eclipse.jetty.nested;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import javax.servlet.ServletInputStream;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.io.Buffer;
import org.eclipse.jetty.io.bio.StreamEndPoint;

public class NestedEndPoint extends StreamEndPoint
{
    public NestedEndPoint(HttpServletRequest request, HttpServletResponse response)
        throws IOException
    {
        super(request.getInputStream(),response.getOutputStream());
    }

    public ServletInputStream getServletInputStream()
    {
        return (ServletInputStream)getInputStream();
    }

}
