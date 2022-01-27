//
// ========================================================================
// Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.apache.jsp;

import java.net.URL;
import java.util.Collection;
import javax.servlet.ServletContext;

import org.apache.jasper.servlet.TldPreScanned;
import org.apache.tomcat.util.descriptor.tld.TldResourcePath;

/**
 * JettyTldPreScanned
 *
 * Change to TldPreScanned to not require that the tlds have been
 * pre-scanned from a jar file, but rather may be files in the
 * file system.
 *
 * This is important for running in the jetty maven plugin
 * environment in multi-module builds, where modules that contain tlds
 * may be in the reactor at the same time as a webapp being run with the
 * plugin. That means that the tlds will be used from their location in
 * the file system, rather than from their assembled jar.
 */
public class JettyTldPreScanned extends TldPreScanned
{
    private final Collection<URL> _jettyPreScannedURLs;

    public JettyTldPreScanned(ServletContext context, boolean namespaceAware, boolean validation, boolean blockExternal, Collection<URL> preScannedTlds)
    {
        super(context, namespaceAware, validation, blockExternal, preScannedTlds);
        _jettyPreScannedURLs = preScannedTlds;
    }

    @Override
    public void scanJars()
    {
        if (_jettyPreScannedURLs != null)
        {
            for (URL url : _jettyPreScannedURLs)
            {
                String str = url.toExternalForm();
                int a = str.indexOf("jar:");
                int b = str.indexOf("META-INF");
                if (b < 0)
                    throw new IllegalStateException("Bad tld url: " + str);

                String path = str.substring(b);
                if (a >= 0)
                {
                    int c = str.indexOf("!/");
                    String fileUrl = str.substring(a + 4, c);
                    try
                    {
                        parseTld(new TldResourcePath(new URL(fileUrl), null, path));
                    }
                    catch (Exception e)
                    {
                        throw new IllegalStateException(e);
                    }
                }
                else
                {
                    try
                    {
                        parseTld(new TldResourcePath(url, null, null));
                    }
                    catch (Exception e)
                    {
                        throw new IllegalStateException(e);
                    }
                }
            }
        }
    }
}
