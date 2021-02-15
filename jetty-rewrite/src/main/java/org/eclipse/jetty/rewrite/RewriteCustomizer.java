//
//  ========================================================================
//  Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
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

package org.eclipse.jetty.rewrite;

import java.io.IOException;

import org.eclipse.jetty.io.RuntimeIOException;
import org.eclipse.jetty.rewrite.handler.RuleContainer;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpConfiguration.Customizer;
import org.eclipse.jetty.server.Request;

public class RewriteCustomizer extends RuleContainer implements Customizer
{
    @Override
    public void customize(Connector connector, HttpConfiguration channelConfig, Request request)
    {
        try
        {
            matchAndApply(request.getPathInfo(), request, request.getResponse());
        }
        catch (IOException e)
        {
            throw new RuntimeIOException(e);
        }
    }
}
