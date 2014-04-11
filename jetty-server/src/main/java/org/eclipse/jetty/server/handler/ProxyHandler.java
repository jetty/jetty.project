//
//  ========================================================================
//  Copyright (c) 1995-2014 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.server.handler;

import org.eclipse.jetty.server.Handler;


/* ------------------------------------------------------------ */
/** ProxyHandler.
 * <p>This class has been renamed to ConnectHandler, as it only implements
 * the CONNECT method (and a ProxyServlet must be used for full proxy handling).
 * @deprecated Use {@link ConnectHandler}
 */
public class ProxyHandler extends ConnectHandler
{
    public ProxyHandler()
    {
        super();
    }

    public ProxyHandler(Handler handler, String[] white, String[] black)
    {
        super(handler,white,black);
    }

    public ProxyHandler(Handler handler)
    {
        super(handler);
    }

    public ProxyHandler(String[] white, String[] black)
    {
        super(white,black);
    }
}
