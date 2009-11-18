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
package org.eclipse.jetty.osgi.boot.warurl.internal;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.net.URL;
import java.net.URLConnection;
import java.security.Permission;
import java.util.List;
import java.util.Map;
import java.util.jar.JarFile;
import java.util.jar.JarInputStream;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;

import org.eclipse.jetty.util.IO;

/**
 * Facade for a URLConnection that will read a jar and substitute its
 * manifest by the manifest provided here.
 * <p>
 * Use Piped streams to avoid having to create temporary files.
 * </p>
 */
public class WarURLConnection extends URLConnection
{

    /**
     * Use PipedOuputStream and PipedInputStream to do the transformation without making
     * a new temporary file ust to replace the manifest.
     * @param newmanifest The new manifest
     * @param rawIn The file input stream or equivalent. not the jar input stream.
     */
    public static InputStream substitueManifest(final Manifest newmanifest,
            final InputStream rawIn) throws IOException
    {
        final PipedOutputStream pOut = new PipedOutputStream();
        PipedInputStream pIn = new PipedInputStream(pOut);
        Runnable run = new Runnable()
        {
            public void run()
            {
                JarInputStream jin = null;
                JarOutputStream dest = null;
                try
                {
                    jin = new JarInputStream(rawIn, false);
                    dest = new JarOutputStream(pOut, newmanifest);
                    ZipEntry next = jin.getNextEntry();
                    while (next != null)
                    {
                        if (next.getName().equalsIgnoreCase(JarFile.MANIFEST_NAME))
                        {
                            continue;
                        }
                        dest.putNextEntry(next);
                        if (next.getSize() > 0)
                        {
                            IO.copy(jin,dest,next.getSize());
                        }
                        next = jin.getNextJarEntry();
                    }
                }
                catch (IOException ioe) {
                    ioe.printStackTrace();
                }
                finally
                {
                    if (dest != null) IO.close(dest);
                    if (jin != null) IO.close(jin);
                    IO.close(pOut);
                }
            }
        };
        Thread th = new Thread(run);
        th.start();
        return pIn;
    }
    
    private Manifest _mf;
    private URLConnection _conn;
    
    /**
     * @param url The file url (for example)
     * @param The manifest to use as a replacement to the jar file inside
     * the file url.
     */
    public WarURLConnection(URL url, Manifest mf) throws IOException
    {
        super(url);
        _conn = url.openConnection();
        _mf = mf;
    }
    @Override
    public void connect() throws IOException
    {
        _conn.connect();
    }
    

    public InputStream getInputStream() throws IOException
    {
        return substitueManifest(_mf, _conn.getInputStream());
    }

    public void addRequestProperty(String key, String value)
    {
        _conn.addRequestProperty(key,value);
    }

    public boolean equals(Object obj)
    {
        return _conn.equals(obj);
    }

    public boolean getAllowUserInteraction()
    {
        return _conn.getAllowUserInteraction();
    }

    public int getConnectTimeout()
    {
        return _conn.getConnectTimeout();
    }

    public Object getContent() throws IOException
    {
        return _conn.getContent();
    }

    public Object getContent(Class[] classes) throws IOException
    {
        return _conn.getContent(classes);
    }

    public String getContentEncoding()
    {
        return _conn.getContentEncoding();
    }

    public int getContentLength()
    {
        return _conn.getContentLength();
    }

    public String getContentType()
    {
        return _conn.getContentType();
    }

    public long getDate()
    {
        return _conn.getDate();
    }

    public boolean getDefaultUseCaches()
    {
        return _conn.getDefaultUseCaches();
    }

    public boolean getDoInput()
    {
        return _conn.getDoInput();
    }

    public boolean getDoOutput()
    {
        return _conn.getDoOutput();
    }

    public long getExpiration()
    {
        return _conn.getExpiration();
    }

    public String getHeaderField(int n)
    {
        return _conn.getHeaderField(n);
    }

    public String getHeaderField(String name)
    {
        return _conn.getHeaderField(name);
    }

    public long getHeaderFieldDate(String name, long Default)
    {
        return _conn.getHeaderFieldDate(name,Default);
    }

    public int getHeaderFieldInt(String name, int Default)
    {
        return _conn.getHeaderFieldInt(name,Default);
    }

    public String getHeaderFieldKey(int n)
    {
        return _conn.getHeaderFieldKey(n);
    }

    public Map<String, List<String>> getHeaderFields()
    {
        return _conn.getHeaderFields();
    }

    public long getIfModifiedSince()
    {
        return _conn.getIfModifiedSince();
    }

    public long getLastModified()
    {
        return _conn.getLastModified();
    }

    public OutputStream getOutputStream() throws IOException
    {
        return _conn.getOutputStream();
    }

    public Permission getPermission() throws IOException
    {
        return _conn.getPermission();
    }

    public int getReadTimeout()
    {
        return _conn.getReadTimeout();
    }

    public Map<String, List<String>> getRequestProperties()
    {
        return _conn.getRequestProperties();
    }

    public String getRequestProperty(String key)
    {
        return _conn.getRequestProperty(key);
    }

    public URL getURL()
    {
        return _conn.getURL();
    }

    public boolean getUseCaches()
    {
        return _conn.getUseCaches();
    }

    public void setAllowUserInteraction(boolean allowuserinteraction)
    {
        _conn.setAllowUserInteraction(allowuserinteraction);
    }

    public void setConnectTimeout(int timeout)
    {
        _conn.setConnectTimeout(timeout);
    }

    public void setDefaultUseCaches(boolean defaultusecaches)
    {
        _conn.setDefaultUseCaches(defaultusecaches);
    }

    public void setDoInput(boolean doinput)
    {
        _conn.setDoInput(doinput);
    }

    public void setDoOutput(boolean dooutput)
    {
        _conn.setDoOutput(dooutput);
    }

    public void setIfModifiedSince(long ifmodifiedsince)
    {
        _conn.setIfModifiedSince(ifmodifiedsince);
    }

    public void setReadTimeout(int timeout)
    {
        _conn.setReadTimeout(timeout);
    }

    public void setRequestProperty(String key, String value)
    {
        _conn.setRequestProperty(key,value);
    }

    public void setUseCaches(boolean usecaches)
    {
        _conn.setUseCaches(usecaches);
    }

    

}
