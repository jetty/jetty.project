// ========================================================================
// Copyright (c) 2004-2009 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.server;

import java.io.IOException;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.util.component.LifeCycle;


public interface Handler extends LifeCycle
{
    /* ------------------------------------------------------------ */
    /** Handle a request.
     * @param target The target of the request - either a URI or a name.
     * @param request The request either as the {@link Request}
     * object or a wrapper of that request. The {@link HttpConnection#getCurrentConnection()} 
     * method can be used access the Request object if required.
     * @param response The response as the {@link Response}
     * object or a wrapper of that request. The {@link HttpConnection#getCurrentConnection()} 
     * method can be used access the Response object if required.
     * @throws IOException
     * @throws ServletException
     */
    public void handle(String target, HttpServletRequest request, HttpServletResponse response)
        throws IOException, ServletException;
    
    public void setServer(Server server);
    public Server getServer();
    
    public void destroy();
    
}

