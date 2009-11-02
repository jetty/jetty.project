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
// Contributors:
//    Hugues Malphettes - initial API and implementation
// ========================================================================
package org.eclipse.jetty.osgi.pde.launch.internal;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.URI;
import java.nio.channels.FileChannel;
import java.util.Enumeration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.eclipse.core.resources.IContainer;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IWorkspaceRoot;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.FileLocator;
import org.eclipse.core.runtime.Path;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.variables.IStringVariableManager;
import org.eclipse.core.variables.VariablesPlugin;
import org.osgi.framework.Bundle;

/**
 * Utility methods related to jetty home and jetty.xml.
 * Default will be something like this:
 * ${workspace_loc}/.metadata/.plugins/org.eclipse.pde.core/Launch Jetty in OSGi
 */
public class JettyHomeHelper
{
    /**
     * Helper
     * @return The bundle that is in charge of starting jetty in OSGi.
     */
    private static Bundle getJettyOSGiBootBundle()
    {
        return Platform.getBundle("org.eclipse.jetty.osgi.boot");
    }
    
    private static String resolveVariables(String target) {
    	IStringVariableManager manager = VariablesPlugin.getDefault().getStringVariableManager();
        try
        {
            return manager.performStringSubstitution(target, false);
        }
        catch (CoreException e)
        {
        }
        return null;
    }
    
    /**
     * Resolves strings that start with &quot;${workspace_loc:&quot;
     * or &quot;${workspace}&quot;
     * Useful as the default configuration area used by pde is:
     * ${workspace_loc}/.metadata/.plugins/org.eclipse.pde.core/${name_of_configuration}
     * @see IStringVariableManager
     * @see org.eclipse.pde.internal.ui.launcher.ConfigurationTemplateBlock
     * @return The container
     */
    public static IContainer getContainerFromWorkspace(String path)
    {
//      /.../theworkspace/.metadata/.plugins/org.eclipse.pde.core/Launch Jetty in OSGi/jettyhome
    	if (path == null || path.trim().length() == 0)
    	{
    		return null;
    	}
        IWorkspaceRoot root = ResourcesPlugin.getWorkspace().getRoot();
        if (path.startsWith("${workspace_loc:"))
        {
        	path = resolveVariables(path);
        	return root.getContainerForLocation(new Path(path).makeAbsolute());
        }
        else if (path.startsWith("${workspace_loc}"))
        {
            path = path.substring("${workspace_loc}".length());
        	path = resolveVariables(path);
            IFolder f = root.getFolder(new Path(path).makeAbsolute());
            if (f.getRawLocation() == null) {
                IContainer c = root.getContainerForLocation(new Path(path).makeAbsolute());
                if (c != null) {
                    return c;
                } else {
                    return null;
                }
            }
        }
        IResource res = root.findMember(path);
        if (res instanceof IContainer)
        {
            return (IContainer) res;
        }
        return null;
    }
    
    
    /**
     * Resolves strings and try to make it a file in the filesystem.
     * @see IStringVariableManager
     * @see org.eclipse.pde.internal.ui.launcher.ConfigurationTemplateBlock
     * @return The container
     */
    public static File getFileOutsideOfWorkspace(String path)
    {
    	if (path == null || path.trim().length() == 0)
    	{
    		return null;
    	}
    	return new File(resolveVariables(path));
        
    }
    
    /**
     * The current content of jettyXml
     */
    public static String getCurrentJettyXml(String jettyHomePath, boolean returnNullIfJettyXmlIsNull)
    {
        IContainer container = JettyHomeHelper.getContainerFromWorkspace(jettyHomePath);
        if (container != null)
        {
            IFile jettyXml = container.getFile(new Path("jettyhome/etc/jetty.xml"));
            if (!jettyXml.exists()) {
                //does not exist at this point:
                //just read the one in the bundle directly.
                return returnNullIfJettyXmlIsNull ? null : JettyHomeHelper.getJettyXmlInsideBootBundle();
            } else {
                //return the one that exists already.
                return JettyHomeHelper.loadIFileAsString(jettyXml);
            }
        }
        else
        {
            File configArea = JettyHomeHelper.getFileOutsideOfWorkspace(jettyHomePath);
            File jettyXml = new File(configArea, "jettyhome/etc/jetty.xml");
            if (jettyXml.exists()) {
                return JettyHomeHelper.loadFileAsString(jettyXml);
            } else {
                //does not exist at this point:
                //just read the one in the bundle directly.
                return returnNullIfJettyXmlIsNull ? null : JettyHomeHelper.getJettyXmlInsideBootBundle();
            }
        }
    }

    
    
    /**
     * @return The content of jetty.xml inside the boot bundle.
     */
    public static String getJettyXmlInsideBootBundle()
    {
       Bundle b = getJettyOSGiBootBundle();
       try
       {
           InputStream is = b.getEntry("/jettyhome/etc/jetty.xml").openStream();
           return loadInputAsString(is);
       }
       catch (IOException e)
       {
           e.printStackTrace();
       }
       return null;
    }
    
    
    
    /**
     * Something really straight forward to read the content of the file.
     * It is just jetty.xml: no need to be really fast and optimized here.
     */
    public static String loadFileAsString(File jettyXml)
    {
        try
        {
            return loadInputAsString(new FileInputStream(jettyXml));
        }
        catch (IOException e)
        {
            e.printStackTrace();
        }
        return null;
    }
        /**
     * Something really straight forward to read the content of the file.
     * It is just jetty.xml: no need to be really fast and optimized here.
     */
    public static String loadIFileAsString(IFile jettyXml)
    {
        try
        {
            return loadInputAsString(jettyXml.getContents());
        }
        catch (CoreException e)
        {
            e.printStackTrace();
        }
        return null;
    }
    
    /**
     * Something really straight forward to read the content of the file.
     * It is just jetty.xml: no need to be really fast and optimized here.
     */
    public static String loadInputAsString(InputStream is)
    {
        StringBuilder sb = new StringBuilder();
        try
        {
            BufferedReader reader = new BufferedReader(new InputStreamReader(is,"UTF-8")); //$NON-NLS-1$
            String newline = System.getProperty("line.separator"); //$NON-NLS-1$
            String line = null;
            while ((line = reader.readLine()) != null)
            {
                sb.append(line + newline);
            }
        }
        catch (IOException e)
        {
            e.printStackTrace();
        }
        finally
        {
            try
            {
                is.close();
            }
            catch (IOException e)
            {
                e.printStackTrace();
            }
        }

        return sb.toString();
    }
    
    public static File resolveJettyHome(String jettyHomeInConfig) {
        IContainer container = JettyHomeHelper.getContainerFromWorkspace(jettyHomeInConfig);
        if (container != null)
        {
            URI rawURI = container.getRawLocationURI();
            if (rawURI != null) {
                //it is null when the folder is not inside a project.
                return new File(rawURI);
            }
        }
        return JettyHomeHelper.getFileOutsideOfWorkspace(jettyHomeInConfig);
    }
    
    /**
     * Look for the jettyhome folder.
     * If empty or it does not exist, extract the default one from the boot.
     * 
     * 
     * @param jettyXml custom content for jetty.xml or null if we keep the default one.
     * @param jettyHomePath path to jettyhome as set by the user.
     */
    public static void setupJettyHomeAndJettyXML(String jettyXml, String jettyHomePath,
            boolean doNothingIfJettyHomeExists)
    throws IOException
    {
        File jettyhome = resolveJettyHome(jettyHomePath);
        if (jettyhome == null)
        {//we probably have a problem now.
            throw new IllegalArgumentException(
                    "Unable to resolve jettyhome " + jettyHomePath);
        }
        if (!jettyhome.exists())
        {
            //does not exist at this point: extract the default one:
            installDefaultJettyHome(jettyhome);
        }
        else if (doNothingIfJettyHomeExists)
        {
            return;
        }
        //check jetty.xml exists.
        File jettyXmlFile = new File(jettyhome, "etc/jetty.xml");
        if (!jettyXmlFile.exists())
        {//we probably have a problem now.
            throw new IllegalArgumentException(
                    "The jetty configuration file must exist: '"
                    + jettyXmlFile.getAbsolutePath());
        }
        if (jettyXml != null) {
            //override the current jettyxml:
            FileOutputStream out = null;
            try
            {
                out = new FileOutputStream(jettyXmlFile);
                OutputStreamWriter writer = new OutputStreamWriter(out, "UTF-8");
                writer.append(jettyXml);
            }
            finally
            {
                if (out != null) try { out.close(); } catch (IOException ioee) {}
            }
        }
    }
    
    /**
     * Actually creates the jettyhome folder template in the location set
     * for the user.
     * Currently unzips the jettyhome folder located in the 
     * org.eclipse.jetty.osgi.boot bundle.
     */
    public static void installDefaultJettyHome(File jettyhomeFolder) throws IOException
    {
        jettyhomeFolder.mkdirs();
        Bundle bootBundle = getJettyOSGiBootBundle();
        File bootBundleFile = FileLocator.getBundleFile(bootBundle);
        
        if (bootBundleFile.getName().endsWith(".jar"))
        {
            //unzip it.
            unzipJettyHomeIntoDirectory(bootBundleFile, jettyhomeFolder);
        }
        else
        {
            //copy the folders.
            copyDirectory(new File(bootBundleFile, "jettyhome"), jettyhomeFolder);
        }
        
    }
    /**
     * @param zipFile
     *            The current jar file for this bundle. contains an archive of
     *            the default jettyhome
     * @param parentOfMagicJettyHome
     *            The folder inside which jettyhome is created.
     */
    private static void unzipJettyHomeIntoDirectory(File thisbundlejar,
            File parentOfMagicJettyHome) throws IOException
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
            if (zipFile != null) try { zipFile.close(); } catch (Throwable t) {}
        }
    }

    private static void copyDirectory(File sourceFile, File destFile) throws IOException
    {
        if (sourceFile.isDirectory())
        {
            if (!destFile.exists())
            {
                destFile.mkdir();
            }
            String files[] = sourceFile.list();
            for (int i = 0; i < files.length; i++)
            {
                copyDirectory(new File(sourceFile,files[i]),new File(destFile,files[i]));
            }
        }
        else
        {
            if (!sourceFile.exists())
            {
                //humf
            }
            else
            {

                copyFile(sourceFile,destFile);
            }
        }
    }
    
    private static void copyFile(File sourceFile, File destFile) throws IOException
    {
        if (!destFile.exists())
        {
            destFile.createNewFile();
        }

        FileChannel source = null;
        FileChannel destination = null;
        try
        {
            source = new FileInputStream(sourceFile).getChannel();
            destination = new FileOutputStream(destFile).getChannel();
            destination.transferFrom(source,0,source.size());
        }
        finally
        {
            if (source != null) { source.close(); }
            if (destination != null) { destination.close(); }
        }
    }

}
