//
//  ========================================================================
//  Copyright (c) 1995-2018 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.util;

import java.io.File;
import java.net.URL;
import java.security.CodeSource;
import java.util.Optional;
import java.util.jar.JarFile;
import java.util.jar.Manifest;

public class ManifestUtils
{
    private ManifestUtils()
    {
    }

    public static Optional<Manifest> getManifest(Class<?> klass)
    {
        try
        {
            CodeSource codeSource = klass.getProtectionDomain().getCodeSource();
            if (codeSource != null)
            {
                URL location = codeSource.getLocation();
                if (location != null)
                {
                    try (JarFile jarFile = new JarFile(new File(location.toURI())))
                    {
                        return Optional.of(jarFile.getManifest());
                    }
                }
            }
            return Optional.empty();
        }
        catch (Throwable x)
        {
            return Optional.empty();
        }
    }
}
