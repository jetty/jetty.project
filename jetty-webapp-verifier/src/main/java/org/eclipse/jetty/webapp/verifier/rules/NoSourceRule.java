// ========================================================================
// Copyright (c) Webtide LLC
// ------------------------------------------------------------------------
// All rights reserved. This program and the accompanying materials
// are made available under the terms of the Eclipse Public License v1.0
// and Apache License v2.0 which accompanies this distribution.
//
// The Eclipse Public License is available at 
// http://www.eclipse.org/legal/epl-v10.html
//
// The Apache License v2.0 is available at
// http://www.apache.org/licenses/LICENSE-2.0.txt
//
// You may elect to redistribute this code under either of these licenses. 
// ========================================================================
package org.eclipse.jetty.webapp.verifier.rules;

import java.io.File;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.eclipse.jetty.webapp.verifier.AbstractArchiveScanningRule;
import org.eclipse.jetty.webapp.verifier.support.PathGlob;

/**
 * <p>
 * Prevent inclusion of source files in webapp. (*.java)
 * </p>
 */
public class NoSourceRule extends AbstractArchiveScanningRule
{
    private String[] sourcePatterns =
    { "*.java" };

    @Override
    public String getDescription()
    {
        return "Prevent inclusion of source files in webapp";
    }

    @Override
    public String getName()
    {
        return "no-source";
    }

    @Override
    public void visitFile(String path, File dir, File file)
    {
        for (String pattern : sourcePatterns)
        {
            if (PathGlob.match(pattern,path))
            {
                error(path,"Source code is forbidden");
            }
        }
    }

    @Override
    public void visitArchiveResource(String path, ZipFile zip, ZipEntry entry)
    {
        for (String pattern : sourcePatterns)
        {
            if (PathGlob.match(pattern,entry.getName()))
            {
                error(path,"Source code is forbidden");
            }
        }
    }
}
