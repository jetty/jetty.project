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

package org.eclipse.jetty.ee10.osgi.boot.warurl.internal;

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
import org.eclipse.jetty.util.resource.Resource;

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
     *
     * @param newmanifest The new manifest
     * @param rawIn The file input stream or equivalent. not the jar input stream.
     * @return InputStream of the replaced manifest file
     * @throws IOException if an I/O error occurs.
     */
    public static InputStream substitueManifest(final Manifest newmanifest,
                                                final InputStream rawIn) throws IOException
    {
        final PipedOutputStream pOut = new PipedOutputStream();
        PipedInputStream pIn = new PipedInputStream(pOut);
        Runnable run = new Runnable()
        {
            @Override
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
                            IO.copy(jin, dest, next.getSize());
                        }
                        next = jin.getNextJarEntry();
                    }
                }
                catch (IOException ioe)
                {
                    ioe.printStackTrace();
                }
                finally
                {
                    if (dest != null)
                        IO.close(dest);
                    if (jin != null)
                        IO.close(jin);
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
     * @param mf The manifest to use as a replacement to the jar file inside
     * the file url.
     * @throws IOException if an I/O error occurs.
     */
    public WarURLConnection(URL url, Manifest mf) throws IOException
    {
        super(url);
        _conn = url.openConnection();
        _conn.setDefaultUseCaches(Resource.getDefaultUseCaches());
        _mf = mf;
    }

    @Override
    public void connect() throws IOException
    {
        _conn.connect();
    }

    @Override
    public InputStream getInputStream() throws IOException
    {
        return substitueManifest(_mf, _conn.getInputStream());
    }

    @Override
    public void addRequestProperty(String key, String value)
    {
        _conn.addRequestProperty(key, value);
    }

    @Override
    public boolean equals(Object obj)
    {
        return _conn.equals(obj);
    }

    @Override
    public boolean getAllowUserInteraction()
    {
        return _conn.getAllowUserInteraction();
    }

    @Override
    public int getConnectTimeout()
    {
        return _conn.getConnectTimeout();
    }

    @Override
    public Object getContent() throws IOException
    {
        return _conn.getContent();
    }

    @Override
    public Object getContent(Class[] classes) throws IOException
    {
        return _conn.getContent(classes);
    }

    @Override
    public String getContentEncoding()
    {
        return _conn.getContentEncoding();
    }

    @Override
    public int getContentLength()
    {
        return _conn.getContentLength();
    }

    @Override
    public String getContentType()
    {
        return _conn.getContentType();
    }

    @Override
    public long getDate()
    {
        return _conn.getDate();
    }

    @Override
    public boolean getDefaultUseCaches()
    {
        return _conn.getDefaultUseCaches();
    }

    @Override
    public boolean getDoInput()
    {
        return _conn.getDoInput();
    }

    @Override
    public boolean getDoOutput()
    {
        return _conn.getDoOutput();
    }

    @Override
    public long getExpiration()
    {
        return _conn.getExpiration();
    }

    @Override
    public String getHeaderField(int n)
    {
        return _conn.getHeaderField(n);
    }

    @Override
    public String getHeaderField(String name)
    {
        return _conn.getHeaderField(name);
    }

    @Override
    public long getHeaderFieldDate(String name, long defaultVal)
    {
        return _conn.getHeaderFieldDate(name, defaultVal);
    }

    @Override
    public int getHeaderFieldInt(String name, int defaultVal)
    {
        return _conn.getHeaderFieldInt(name, defaultVal);
    }

    @Override
    public String getHeaderFieldKey(int n)
    {
        return _conn.getHeaderFieldKey(n);
    }

    @Override
    public Map<String, List<String>> getHeaderFields()
    {
        return _conn.getHeaderFields();
    }

    @Override
    public long getIfModifiedSince()
    {
        return _conn.getIfModifiedSince();
    }

    @Override
    public long getLastModified()
    {
        return _conn.getLastModified();
    }

    @Override
    public OutputStream getOutputStream() throws IOException
    {
        return _conn.getOutputStream();
    }

    @Override
    public Permission getPermission() throws IOException
    {
        return _conn.getPermission();
    }

    @Override
    public int getReadTimeout()
    {
        return _conn.getReadTimeout();
    }

    @Override
    public Map<String, List<String>> getRequestProperties()
    {
        return _conn.getRequestProperties();
    }

    @Override
    public String getRequestProperty(String key)
    {
        return _conn.getRequestProperty(key);
    }

    @Override
    public URL getURL()
    {
        return _conn.getURL();
    }

    @Override
    public boolean getUseCaches()
    {
        return _conn.getUseCaches();
    }

    @Override
    public void setAllowUserInteraction(boolean allowuserinteraction)
    {
        _conn.setAllowUserInteraction(allowuserinteraction);
    }

    @Override
    public void setConnectTimeout(int timeout)
    {
        _conn.setConnectTimeout(timeout);
    }

    @Override
    public void setDefaultUseCaches(boolean defaultusecaches)
    {
        _conn.setDefaultUseCaches(defaultusecaches);
    }

    @Override
    public void setDoInput(boolean doinput)
    {
        _conn.setDoInput(doinput);
    }

    @Override
    public void setDoOutput(boolean dooutput)
    {
        _conn.setDoOutput(dooutput);
    }

    @Override
    public void setIfModifiedSince(long ifmodifiedsince)
    {
        _conn.setIfModifiedSince(ifmodifiedsince);
    }

    @Override
    public void setReadTimeout(int timeout)
    {
        _conn.setReadTimeout(timeout);
    }

    @Override
    public void setRequestProperty(String key, String value)
    {
        _conn.setRequestProperty(key, value);
    }

    @Override
    public void setUseCaches(boolean usecaches)
    {
        _conn.setUseCaches(usecaches);
    }
}
