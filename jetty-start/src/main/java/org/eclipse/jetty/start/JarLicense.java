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

package org.eclipse.jetty.start;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import java.util.jar.JarFile;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;

public class JarLicense
{
    private static final Pattern MANIFEST_MF_LICENSE = Pattern.compile("Bundle-License: https?://([^ ,]+)");

    /**
     * Get the license from the given jar file.
     * Method tries to find the license from 3 places
     * 1. MANIFEST.MF
     * 2. pom.xml
     * 3. LICENSE file
     * @param file Jar File
     * @return License name or (none specified) if license is absent
     */
    public static String getLicense(File file)
    {
        String nonSpecified = "(none specified)";
        try (JarFile jar = new JarFile(file))
        {
            return findFirstNonNullLicenseOr(
                List.of(
                    JarLicense::findFromManifest,
                    JarLicense::findFromPOM,
                    JarLicense::findFromLicenseFile
                ),
                jar,
                nonSpecified
            );
        }
        catch (IOException e)
        {
            return "(error: " + e.getClass().getSimpleName() + " " + e.getMessage() + ")";
        }
    }

    /**
     * Method lazily tries to find a license from a list of functions
     * @param functions List of functions that find license
     * @param jarFile JarFile
     * @param or Default value if license wasn't found
     * @return Jar license
     */
    public static String findFirstNonNullLicenseOr(List<Function<JarFile, String>> functions, JarFile jarFile, String or)
    {
        return functions.stream()
            .map(licenseFunction -> licenseFunction.apply(jarFile))
            .filter(Objects::nonNull)
            .findFirst()
            .orElse(or);
    }

    private static String findFromLicenseFile(JarFile jar)
    {
        //TODO: implement
        return null;
    }

    private static String findFromPOM(JarFile jar)
    {
        //TODO: implement
        return null;
    }

    private static String findFromManifest(JarFile jar)
    {
        ZipEntry manifest = jar.getEntry("META-INF/MANIFEST.MF");
        if (manifest == null)
            return null;
        try
        {
            InputStream licenseFileStream = jar.getInputStream(manifest);
            if (licenseFileStream == null)
                return null;
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(licenseFileStream)))
            {
                String line;
                while ((line = reader.readLine()) != null)
                {
                    Matcher matcher = MANIFEST_MF_LICENSE.matcher(line);
                    if (matcher.find())
                    {
                        String licenseUrl = matcher.group(1);
                        String licenseName = AllOSSLicenses.LICENSE_URL_TO_NAME.get(licenseUrl);
                        if (licenseName != null)
                            return licenseName;
                    }
                }
            }
            return null;
        }
        catch (IOException e)
        {
            return "(error: " + e.getClass().getSimpleName() + " " + e.getMessage() + ")";
        }
    }
}
