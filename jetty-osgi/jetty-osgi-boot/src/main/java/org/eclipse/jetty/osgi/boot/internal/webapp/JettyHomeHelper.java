// ========================================================================
// Copyright (c) 2009 Intalio, Inc.
// ------------------------------------------------------------------------
// All rights reserved. This program and the accompanying materials
// are made available under the terms of the Eclipse Public License v1.0
// and Apache License v2.0 which accompanies this distribution.
// The Eclipse Public License is available at 
// http://www.eclipse.org/legal/epl-v10.html
// The Apache License v2.0 is available at
// http://www.opensource.org/licenses/apache2.0.php
// You may elect to redistribute this code under either of these licenses. 
// ========================================================================
package org.eclipse.jetty.osgi.boot.internal.webapp;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URL;
import java.util.Enumeration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.eclipse.jetty.util.URIUtil;

/**
 * <p>
 * Magically extract the jettyhome folder from this bundle's jar place it 
 * somewhere in the file-system. Currently we do this only when we detect a system
 * property 'jetty.magic.home.parent' or if we are inside the pde in dev mode.
 * In dev mode we use the osgi.configuration.area folder.
 * </p>
 * <p>
 * This work is done through the jetty launch configuration inside the
 * "Jetty Config" tab. We could choose to remove this code at some point
 * although it does not hurt and it helps for users who don't go through the
 * jetty launch.
 * </p>
 */
class JettyHomeHelper
{

    /** only magically extract jettyhome if we are inside the pde. */
    static boolean magic_install_only_in_pde = Boolean.valueOf(
            System.getProperty("jetty.magic.home.pde.only","true"));

    /**
     * Hack for eclipse-PDE. When no jetty.home was set, detect if we running
     * inside eclipse-PDE in development mode. In that case extract the jettyhome folder
     * embedded inside this plugin inside the configuration area folder.
     * It is specific to the workspace. Set the folder as jetty.home. If the folder already
     * exist don't extract it again.
     * <p>
     * If we are not pde dev mode, the same but look in the installation folder of eclipse itself.
     * </p>
     * 
     * @return
     * @throws URISyntaxException
     */
    static String setupJettyHomeInEclipsePDE(File thisbundlejar)
    {
        File ecFolder = getParentFolderOfMagicHome();
        if (ecFolder == null || !ecFolder.exists())
        {
            return null;
        }
        File jettyhome = new File(ecFolder,"jettyhome");
        String path;
        try
        {
            path = jettyhome.getCanonicalPath();
            if (jettyhome.exists())
            {
                System.setProperty("jetty.home",path);
                return path;
            }
            else
            {
                // now grab the jar and unzip the relevant portion
                unzipJettyHomeIntoDirectory(thisbundlejar,ecFolder);
                System.setProperty("jetty.home",path);
                return path;
            }
        }
        catch (IOException e)
        {
            e.printStackTrace();
        }
        return null;
    }

    /**
     * @return true when we are currently being run by the pde in development mode.
     */
    private static boolean isPDEDevelopment()
    {
        String eclipseCommands = System.getProperty("eclipse.commands");
        // detect if we are being run from the pde: ie during development.
        return eclipseCommands != null && eclipseCommands.indexOf("-dev") != -1
                && (eclipseCommands.indexOf("-dev\n") != -1
                        || eclipseCommands.indexOf("-dev\r") != -1
                        || eclipseCommands.indexOf("-dev ") != -1);
    }

    // /**
    // * @return
    // */
    // private static File getEclipseInstallationDir() {
    // return getFile(System.getProperty("eclipse.home.location",
    // System.getProperty("osgi.install.area")));
    // }

    /**
     * @return
     */
    private static File getConfigurationAreaDirectory()
    {
        return getFile(System.getProperty("osgi.configuration.area"));
    }

    /**
     * @param zipFile
     *            The current jar file for this bundle. contains an archive of the default jettyhome
     * @param parentOfMagicJettyHome
     *            The folder inside which jettyhome is created.
     */
    private static void unzipJettyHomeIntoDirectory(File thisbundlejar, File parentOfMagicJettyHome) throws IOException
    {
        ZipFile zipFile = null;
        try
        {
            zipFile = new ZipFile(thisbundlejar);
            Enumeration<? extends ZipEntry> files = zipFile.entries();
            File f = null;
            FileOutputStream fos = null;

            while (files.hasMoreElements())
            {
                try
                {
                    ZipEntry entry = files.nextElement();
                    String entryName = entry.getName();
                    if (!entryName.startsWith("jettyhome"))
                    {
                        continue;
                    }

                    InputStream eis = zipFile.getInputStream(entry);
                    byte[] buffer = new byte[1024];
                    int bytesRead = 0;
                    f = new File(parentOfMagicJettyHome,entry.getName());

                    if (entry.isDirectory())
                    {
                        f.mkdirs();
                    }
                    else
                    {
                        f.getParentFile().mkdirs();
                        f.createNewFile();
                        fos = new FileOutputStream(f);
                        while ((bytesRead = eis.read(buffer)) != -1)
                        {
                            fos.write(buffer,0,bytesRead);
                        }
                    }
                }
                catch (IOException e)
                {
                    e.printStackTrace();
                    continue;
                }
                finally
                {
                    if (fos != null)
                    {
                        try
                        {
                            fos.close();
                        }
                        catch (IOException e)
                        {
                        }
                        fos = null;
                    }
                }
            }
        }
        finally
        {
            if (zipFile != null)
                try
                {
                    zipFile.close();
                }
                catch (Throwable t)
                {
                }
        }
    }

    /**
     * Look for the parent folder that contains jettyhome.
     * Can be specified by the sys property jetty.magic.home.parent or if 
     * inside the pde will default on the
     * configuration area. Otherwise returns null.
     * 
     * @return The folder inside which jettyhome should be placed.
     */
    private static File getParentFolderOfMagicHome()
    {
        // for (java.util.Map.Entry<Object, Object> e : System.getProperties().entrySet()) {
        // System.err.println(e.getKey() + " -> " + e.getValue());
        // }
        String magicParent = WebappRegistrationHelper.stripQuotesIfPresent(System.getProperty("jetty.magic.home.parent"));
        String magicParentValue = magicParent != null
                ? System.getProperty(magicParent) : null;
        File specifiedMagicParent = magicParentValue != null
                ? getFile(magicParentValue) // in that case it was pointing to another system property.
                : getFile(magicParent); // in that case it was directly a file.
        if (specifiedMagicParent != null && specifiedMagicParent.exists())
        {
            return specifiedMagicParent;
        }
        if (isPDEDevelopment())
        {
            return getConfigurationAreaDirectory();
        }
        return null;
    }

    /**
     * Be flexible with the url/uri/path that can be the value of the various system properties.
     * 
     * @param file
     * @return a file. might not exist.
     */
    private static File getFile(String file)
    {
        if (file == null)
        {
            return null;
        }
        file = WebappRegistrationHelper.stripQuotesIfPresent(file);
        try
        {
            if (file.startsWith("file:/"))
            {
                if (!file.startsWith("file://"))
                {
                    return new File(new URI(URIUtil.encodePath(file)));
                }
                else
                {
                    return new File(new URL(file).toURI());
                }
            }
            else
            {
                return new File(file);
            }
        }
        catch (Throwable t)
        {
            t.printStackTrace();
            return new File(file);
        }

    }
}
