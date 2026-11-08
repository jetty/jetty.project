//
// ========================================================================
// Copyright (c) 1995 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.tests.tck.ee11;

import java.net.URISyntaxException;
import java.net.URL;

import org.eclipse.jetty.ee11.webapp.WebAppContext;
import org.jboss.arquillian.container.jetty.embedded_12_1_ee11.WebAppContextProcessor;
import org.jboss.shrinkwrap.api.Archive;

/**
 * The servlet_spec_fragment_web test expects a defaults descriptor that differs from Jetty's own
 * webdefault-ee11.xml, so swap ours in before that web application starts. Every other deployment
 * is left alone.
 */
public class JettyErrorHandlerWebAppContextProcessor implements WebAppContextProcessor
{
    @Override
    public void process(WebAppContext webAppContext, Archive<?> archive)
    {
        if (!"servlet_spec_fragment_web.war".equals(archive.getName()))
            return;

        URL url = Thread.currentThread().getContextClassLoader().getResource("servlet_spec_fragment_web/webdefault.xml");
        try
        {
            webAppContext.setDefaultsDescriptor(url.toURI().getPath());
        }
        catch (URISyntaxException e)
        {
            throw new RuntimeException(e.getMessage(), e);
        }
    }
}
