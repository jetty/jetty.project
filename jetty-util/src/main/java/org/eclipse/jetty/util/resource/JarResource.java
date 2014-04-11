//
//  ========================================================================
//  Copyright (c) 1995-2014 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.util.resource;

import java.io.File;
import java.io.FileOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.JarURLConnection;
import java.net.URL;
import java.util.jar.JarEntry;
import java.util.jar.JarInputStream;
import java.util.jar.Manifest;

import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.URIUtil;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;


/* ------------------------------------------------------------ */
public class JarResource extends URLResource
{
    private static final Logger LOG = Log.getLogger(JarResource.class);
    protected JarURLConnection _jarConnection;
    
    /* -------------------------------------------------------- */
    JarResource(URL url)
    {
        super(url,null);
    }

    /* ------------------------------------------------------------ */
    JarResource(URL url, boolean useCaches)
    {
        super(url, null, useCaches);
    }
    
    /* ------------------------------------------------------------ */
    @Override
    public synchronized void release()
    {
        _jarConnection=null;
        super.release();
    }
    
    /* ------------------------------------------------------------ */
    @Override
    protected synchronized boolean checkConnection()
    {
        super.checkConnection();
        try
        {
            if (_jarConnection!=_connection)
                newConnection();
        }
        catch(IOException e)
        {
            LOG.ignore(e);
            _jarConnection=null;
        }
        
        return _jarConnection!=null;
    }

    /* ------------------------------------------------------------ */
    /**
     * @throws IOException Sub-classes of <code>JarResource</code> may throw an IOException (or subclass) 
     */
    protected void newConnection() throws IOException
    {
        _jarConnection=(JarURLConnection)_connection;
    }
    
    /* ------------------------------------------------------------ */
    /**
     * Returns true if the respresenetd resource exists.
     */
    @Override
    public boolean exists()
    {
        if (_urlString.endsWith("!/"))
            return checkConnection();
        else
            return super.exists();
    }    

    /* ------------------------------------------------------------ */
    @Override
    public File getFile()
        throws IOException
    {
        return null;
    }
    
    /* ------------------------------------------------------------ */
    @Override
    public InputStream getInputStream()
        throws java.io.IOException
    {     
        checkConnection();
        if (!_urlString.endsWith("!/"))
            return new FilterInputStream(super.getInputStream()) 
            {
                @Override
                public void close() throws IOException {this.in=IO.getClosedStream();}
            };

        URL url = new URL(_urlString.substring(4,_urlString.length()-2));      
        InputStream is = url.openStream();
        return is;
    }
    
    /* ------------------------------------------------------------ */
    @Override
    public void copyTo(File directory)
        throws IOException
    {
        if (!exists())
            return;
        
        if(LOG.isDebugEnabled())
            LOG.debug("Extract "+this+" to "+directory);
        
        String urlString = this.getURL().toExternalForm().trim();
        int endOfJarUrl = urlString.indexOf("!/");
        int startOfJarUrl = (endOfJarUrl >= 0?4:0);
        
        if (endOfJarUrl < 0)
            throw new IOException("Not a valid jar url: "+urlString);
        
        URL jarFileURL = new URL(urlString.substring(startOfJarUrl, endOfJarUrl));
        String subEntryName = (endOfJarUrl+2 < urlString.length() ? urlString.substring(endOfJarUrl + 2) : null);
        boolean subEntryIsDir = (subEntryName != null && subEntryName.endsWith("/")?true:false);
      
        if (LOG.isDebugEnabled()) 
            LOG.debug("Extracting entry = "+subEntryName+" from jar "+jarFileURL);
        
        InputStream is = jarFileURL.openConnection().getInputStream();
        JarInputStream jin = new JarInputStream(is);
        JarEntry entry;
        boolean shouldExtract;
        while((entry=jin.getNextJarEntry())!=null)
        {
            String entryName = entry.getName();
            if ((subEntryName != null) && (entryName.startsWith(subEntryName)))
            { 
                // is the subentry really a dir?
                if (!subEntryIsDir && subEntryName.length()+1==entryName.length() && entryName.endsWith("/"))
                        subEntryIsDir=true;
                
                //if there is a particular subEntry that we are looking for, only
                //extract it.
                if (subEntryIsDir)
                {
                    //if it is a subdirectory we are looking for, then we
                    //are looking to extract its contents into the target
                    //directory. Remove the name of the subdirectory so
                    //that we don't wind up creating it too.
                    entryName = entryName.substring(subEntryName.length());
                    if (!entryName.equals(""))
                    {
                        //the entry is 
                        shouldExtract = true;                   
                    }
                    else
                        shouldExtract = false;
                }
                else
                    shouldExtract = true;
            }
            else if ((subEntryName != null) && (!entryName.startsWith(subEntryName)))
            {
                //there is a particular entry we are looking for, and this one
                //isn't it
                shouldExtract = false;
            }
            else
            {
                //we are extracting everything
                shouldExtract =  true;
            }
                
            
            if (!shouldExtract)
            {
                if (LOG.isDebugEnabled()) 
                    LOG.debug("Skipping entry: "+entryName);
                continue;
            }
                
            String dotCheck = entryName.replace('\\', '/');   
            dotCheck = URIUtil.canonicalPath(dotCheck);
            if (dotCheck == null)
            {
                if (LOG.isDebugEnabled()) 
                    LOG.debug("Invalid entry: "+entryName);
                continue;
            }

            File file=new File(directory,entryName);
     
            if (entry.isDirectory())
            {
                // Make directory
                if (!file.exists())
                    file.mkdirs();
            }
            else
            {
                // make directory (some jars don't list dirs)
                File dir = new File(file.getParent());
                if (!dir.exists())
                    dir.mkdirs();

                // Make file
                FileOutputStream fout = null;
                try
                {
                    fout = new FileOutputStream(file);
                    IO.copy(jin,fout);
                }
                finally
                {
                    IO.close(fout);
                }

                // touch the file.
                if (entry.getTime()>=0)
                    file.setLastModified(entry.getTime());
            }
        }
        
        if ((subEntryName == null) || (subEntryName != null && subEntryName.equalsIgnoreCase("META-INF/MANIFEST.MF")))
        {
            Manifest manifest = jin.getManifest();
            if (manifest != null)
            {
                File metaInf = new File (directory, "META-INF");
                metaInf.mkdir();
                File f = new File(metaInf, "MANIFEST.MF");
                FileOutputStream fout = new FileOutputStream(f);
                manifest.write(fout);
                fout.close();   
            }
        }
        IO.close(jin);
    }   
    
    public static Resource newJarResource(Resource resource) throws IOException
    {
        if (resource instanceof JarResource)
            return resource;
        return Resource.newResource("jar:" + resource + "!/");
    }
}
