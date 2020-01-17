//
//  ========================================================================
//  Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
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

package org.eclipse.jetty.server;

import java.io.IOException;
import java.net.URLClassLoader;

import org.eclipse.jetty.util.component.Dumpable;
import org.eclipse.jetty.util.component.DumpableCollection;

public class ClassLoaderDump implements Dumpable
{
    final ClassLoader _loader;

    public ClassLoaderDump(ClassLoader loader)
    {
        _loader = loader;
    }

    @Override
    public String dump()
    {
        return Dumpable.dump(this);
    }

    @Override
    public void dump(Appendable out, String indent) throws IOException
    {
        if (_loader == null)
            out.append("No ClassLoader\n");
        else if (_loader instanceof Dumpable)
        {
            ((Dumpable)_loader).dump(out, indent);
        }
        else if (_loader instanceof URLClassLoader)
        {
            String loader = _loader.toString();
            DumpableCollection urls = DumpableCollection.fromArray("URLs", ((URLClassLoader)_loader).getURLs());
            ClassLoader parent = _loader.getParent();
            if (parent == null)
                Dumpable.dumpObjects(out, indent, loader, urls);
            else if (parent == Server.class.getClassLoader())
                Dumpable.dumpObjects(out, indent, loader, urls, parent.toString());
            else if (parent instanceof Dumpable)
                Dumpable.dumpObjects(out, indent, loader, urls, parent);
            else
                Dumpable.dumpObjects(out, indent, loader, urls, new ClassLoaderDump(parent));
        }
        else
        {
            String loader = _loader.toString();
            ClassLoader parent = _loader.getParent();
            if (parent == null)
                Dumpable.dumpObject(out, loader);
            if (parent == Server.class.getClassLoader())
                Dumpable.dumpObjects(out, indent, loader, parent.toString());
            else if (parent instanceof Dumpable)
                Dumpable.dumpObjects(out, indent, loader, parent);
            else if (parent != null)
                Dumpable.dumpObjects(out, indent, loader, new ClassLoaderDump(parent));
        }
    }
}
