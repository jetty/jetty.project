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
        else if (_loader.getDefinedPackages() != null)
        {
            DumpableCollection packages = DumpableCollection.from("packages", (Object[])_loader.getDefinedPackages());
            ClassLoader parent = _loader.getParent();
            if (parent == Server.class.getClassLoader())
                Dumpable.dumpObjects(out, indent, _loader, packages, "Server loader: " + parent);
            else if (parent instanceof Dumpable)
                Dumpable.dumpObjects(out, indent, _loader, packages, parent);
            else if (parent != null)
                Dumpable.dumpObjects(out, indent, _loader, packages, new ClassLoaderDump(parent));
            else
                Dumpable.dumpObjects(out, indent, _loader, packages);
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
