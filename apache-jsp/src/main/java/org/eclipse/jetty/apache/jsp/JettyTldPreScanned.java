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

    /**
     * @see org.apache.jasper.servlet.TldPreScanned#scanJars()
     */
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
