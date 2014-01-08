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

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;

/**
 * Simple Start .INI handler
 */
public class StartIni extends TextFile
{
    public StartIni(File file) throws FileNotFoundException, IOException
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
                super.addUniqueLine("--module=" + part);
            }
        }
        else
        {
            super.addUniqueLine(line);
        }
    }
}
