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

package org.eclipse.jetty.osgi.boot.warurl;

import java.io.File;
import java.io.IOException;
import java.net.JarURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.util.jar.Manifest;

import org.eclipse.jetty.osgi.boot.warurl.internal.WarBundleManifestGenerator;
import org.eclipse.jetty.osgi.boot.warurl.internal.WarURLConnection;
import org.eclipse.jetty.util.URIUtil;
import org.eclipse.jetty.util.resource.Resource;
import org.osgi.service.url.AbstractURLStreamHandlerService;

/**
 * RFC-66: support for the "war" protocol We are reusing the parsing of the
 * query string from jetty. If we wanted to not depend on jetty at all we could
 * duplicate that method here
 */
public class WarUrlStreamHandler extends AbstractURLStreamHandlerService
{

    /**
     * @param url The url with a war scheme
     */
    @Override
    public URLConnection openConnection(URL url) throws IOException
    {
        // remove the war scheme.
        URL actual = new URL(url.toString().substring("war:".length()));

        // let's do some basic tests: see if this is a folder or not.
        // if it is a folder. we will try to support it.
        if (actual.getProtocol().equals("file"))
        {
            File file = new File(URIUtil.encodePath(actual.getPath()));
            if (file.exists())
            {
                if (file.isDirectory())
                {
                    // TODO (not mandatory for rfc66 though)
                }
            }
        }

        // if (actual.toString().startsWith("file:/") && ! actual.to)
        URLConnection ori = (URLConnection)actual.openConnection();
        ori.setDefaultUseCaches(Resource.getDefaultUseCaches());
        JarURLConnection jarOri = null;
        try
        {
            if (ori instanceof JarURLConnection)
            {
                jarOri = (JarURLConnection)ori;
            }
            else
            {
                jarOri = (JarURLConnection)new URL("jar:" + actual.toString() + "!/").openConnection();
                jarOri.setDefaultUseCaches(Resource.getDefaultUseCaches());
            }
            Manifest mf = WarBundleManifestGenerator.createBundleManifest(jarOri.getManifest(), url, jarOri.getJarFile());
            try
            {
                jarOri.getJarFile().close();
                jarOri = null;
            }
            catch (Throwable ignored)
            {
            }
            return new WarURLConnection(actual, mf);
        }
        finally
        {
            if (jarOri != null)
                try
                {
                    jarOri.getJarFile().close();
                }
                catch (Throwable ignored)
                {
                }
        }
    }
}
