//
//  ========================================================================
//  Copyright (c) 1995-2017 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.servlets.mocks;

import java.util.Collections;
import java.util.Enumeration;
import javax.servlet.FilterConfig;
import javax.servlet.ServletContext;
import org.eclipse.jetty.server.handler.ContextHandler;


public class MockFilterConfig implements FilterConfig {

    @Override
    public String getFilterName() {
        return "";
    }

    @Override
    public ServletContext getServletContext() {
        return new ContextHandler.StaticContext();
    }

    @Override
    public String getInitParameter(String string) {
        return null;
    }

    @Override
    public Enumeration<String> getInitParameterNames() {
        return Collections.emptyEnumeration();
    }
    
}
