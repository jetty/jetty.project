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

package org.eclipse.jetty.server;


import org.eclipse.jetty.server.handler.ContextHandler;

/**
 * ContextHandler for testing that implements some
 * CharEncoding methods from the servlet spec.
 *
 */
public class CharEncodingContextHandler extends ContextHandler
{
    class Context extends ContextHandler.Context
    {
        @Override
        public String getRequestCharacterEncoding()
        {
            return getDefaultRequestCharacterEncoding();
        }

        @Override
        public String getResponseCharacterEncoding()
        {
            return getDefaultResponseCharacterEncoding();
        }
    }
    
    public CharEncodingContextHandler()
    {
        super();
        _scontext = new Context();
    }
}