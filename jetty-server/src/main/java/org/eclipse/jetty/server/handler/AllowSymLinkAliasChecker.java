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

package org.eclipse.jetty.server.handler;

import java.io.File;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;

import org.eclipse.jetty.server.handler.ContextHandler.AliasCheck;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.resource.Resource;


/* ------------------------------------------------------------ */
/** Symbolic Link AliasChecker.
 * <p>An instance of this class can be registered with {@link ContextHandler#addAliasCheck(AliasCheck)}
 * to check resources that are aliased to other locations.   The checker uses the 
 * Java {@link Files#readSymbolicLink(Path)} and {@link Path#toRealPath(java.nio.file.LinkOption...)}
 * APIs to check if a file is aliased with symbolic links.</p>
 */
public class AllowSymLinkAliasChecker implements AliasCheck
{
    private static final Logger LOG = Log.getLogger(AllowSymLinkAliasChecker.class);
    
    @Override
    public boolean check(String path, Resource resource)
    {
        try
        {
            File file =resource.getFile();
            if (file==null)
                return false;
            
            // If the file exists
            if (file.exists())
            {
                // we can use the real path method to check the symlinks resolve to the alias
                URI real = file.toPath().toRealPath().toUri();
                if (real.equals(resource.getAlias()))
                {
                    LOG.debug("Allow symlink {} --> {}",resource,real);
                    return true;
                }
            }
            else
            {
                // file does not exists, so we have to walk the path and links ourselves.
                Path p = file.toPath().toAbsolutePath();
                File d = p.getRoot().toFile();
                for (Path e:p)
                {
                    d=new File(d,e.toString());
                    
                    while (d.exists() && Files.isSymbolicLink(d.toPath()))
                    {
                        Path link=Files.readSymbolicLink(d.toPath());
                        if (!link.isAbsolute())
                            link=link.resolve(d.toPath());
                        d=link.toFile().getAbsoluteFile().getCanonicalFile();
                    }
                }
                if (resource.getAlias().equals(d.toURI()))
                {
                    LOG.debug("Allow symlink {} --> {}",resource,d);
                    return true;
                }
            }
        }
        catch(Exception e)
        {
            e.printStackTrace();
            LOG.ignore(e);
        }
        return false;
    }

}
