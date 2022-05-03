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

package org.eclipse.jetty.util;

import java.io.File;
import java.net.URL;
import java.security.CodeSource;
import java.util.Optional;
import java.util.jar.JarFile;
import java.util.jar.Manifest;

public class ManifestUtils
{
    // IMPORTANT NOTE: This class cannot use Logging, as this class is used by jetty-start

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

    /**
     * <p>Attempts to return the version of the jar/module for the given class.</p>
     * <p>First, retrieves the {@code Implementation-Version} main attribute of the manifest;
     * if that is missing, retrieves the JPMS module version (via reflection);
     * if that is missing, returns an empty Optional.</p>
     *
     * @param klass the class of the jar/module to retrieve the version
     * @return the jar/module version, or an empty Optional
     */
    public static Optional<String> getVersion(Class<?> klass)
    {
        Optional<String> version = getManifest(klass).map(Manifest::getMainAttributes)
            .map(attributes -> attributes.getValue("Implementation-Version"));
        if (version.isPresent())
            return version;

        try
        {
            Object module = klass.getClass().getMethod("getModule").invoke(klass);
            Object descriptor = module.getClass().getMethod("getDescriptor").invoke(module);
            return (Optional<String>)descriptor.getClass().getMethod("rawVersion").invoke(descriptor);
        }
        catch (Throwable x)
        {
            return Optional.empty();
        }
    }
}
