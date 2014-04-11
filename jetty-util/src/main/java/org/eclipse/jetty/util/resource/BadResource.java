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
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;


/* ------------------------------------------------------------ */
/** Bad Resource.
 *
 * A Resource that is returned for a bade URL.  Acts as a resource
 * that does not exist and throws appropriate exceptions.
 *
 * 
 */
class BadResource extends URLResource
{
    /* ------------------------------------------------------------ */
    private String _message=null;
        
    /* -------------------------------------------------------- */
    BadResource(URL url,  String message)
    {
        super(url,null);
        _message=message;
    }
    

    /* -------------------------------------------------------- */
    @Override
    public boolean exists()
    {
        return false;
    }
        
    /* -------------------------------------------------------- */
    @Override
    public long lastModified()
    {
        return -1;
    }

    /* -------------------------------------------------------- */
    @Override
    public boolean isDirectory()
    {
        return false;
    }

    /* --------------------------------------------------------- */
    @Override
    public long length()
    {
        return -1;
    }
        
        
    /* ------------------------------------------------------------ */
    @Override
    public File getFile()
    {
        return null;
    }
        
    /* --------------------------------------------------------- */
    @Override
    public InputStream getInputStream() throws IOException
    {
        throw new FileNotFoundException(_message);
    }
        
    /* --------------------------------------------------------- */
    @Override
    public OutputStream getOutputStream()
        throws java.io.IOException, SecurityException
    {
        throw new FileNotFoundException(_message);
    }
        
    /* --------------------------------------------------------- */
    @Override
    public boolean delete()
        throws SecurityException
    {
        throw new SecurityException(_message);
    }

    /* --------------------------------------------------------- */
    @Override
    public boolean renameTo( Resource dest)
        throws SecurityException
    {
        throw new SecurityException(_message);
    }

    /* --------------------------------------------------------- */
    @Override
    public String[] list()
    {
        return null;
    }

    /* ------------------------------------------------------------ */
    @Override
    public void copyTo(File destination)
        throws IOException
    {
        throw new SecurityException(_message);
    }
    
    /* ------------------------------------------------------------ */
    @Override
    public String toString()
    {
        return super.toString()+"; BadResource="+_message;
    }
    
}
