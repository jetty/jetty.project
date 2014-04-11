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

package org.eclipse.jetty.util.component;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.resource.Resource;

public class FileDestroyable implements Destroyable
{
    private static final Logger LOG = Log.getLogger(FileDestroyable.class);
    final List<File> _files = new ArrayList<File>();

    public FileDestroyable()
    {
    }
    
    public FileDestroyable(String file) throws IOException
    {
        _files.add(Resource.newResource(file).getFile());
    }
    
    public FileDestroyable(File file)
    {
        _files.add(file);
    }
    
    public void addFile(String file) throws IOException
    {
        _files.add(Resource.newResource(file).getFile());
    }
    
    public void addFile(File file)
    {
        _files.add(file);
    }
    
    public void addFiles(Collection<File> files)
    {
        _files.addAll(files);
    }
    
    public void removeFile(String file) throws IOException
    {
        _files.remove(Resource.newResource(file).getFile());
    }
    
    public void removeFile(File file)
    {
        _files.remove(file);
    }
    
    public void destroy()
    {
        for (File file : _files)
        {
            if (file.exists())
            {
                LOG.debug("Destroy {}",file);
                IO.delete(file);
            }
        }
    }

}
