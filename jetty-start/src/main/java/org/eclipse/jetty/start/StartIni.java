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

package org.eclipse.jetty.start;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Simple Start .INI handler
 */
public class StartIni extends TextFile
{
    private Path basedir;

    public StartIni(Path file) throws IOException
    {
        super(file);
    }

    @Override
    public void addUniqueLine(String line)
    {
        if (line.startsWith("--module="))
        {
            int idx = line.indexOf('=');
            String value = line.substring(idx + 1);
            for (String part : value.split(","))
            {
                super.addUniqueLine("--module=" + expandBaseDir(part));
            }
        }
        else
        {
            super.addUniqueLine(expandBaseDir(line));
        }
    }

    private String expandBaseDir(String line)
    {
        if (line == null)
        {
            return line;
        }

        return line.replace("${start.basedir}",basedir.toString());
    }

    @Override
    public void init()
    {
        basedir = getFile().getParent().toAbsolutePath();
    }

    public Path getBaseDir()
    {
        return basedir;
    }
}
