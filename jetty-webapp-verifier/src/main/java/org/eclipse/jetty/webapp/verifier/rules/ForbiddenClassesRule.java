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
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.eclipse.jetty.webapp.verifier.AbstractArchiveScanningRule;

/**
 * ForbiddenClassesVerifier checks the various classes available to the Webapp to ensure that they do not contain and
 * forbidden classes.
 */
public class ForbiddenClassesRule extends AbstractArchiveScanningRule
{
    private Map<String, Pattern> classPatterns = new HashMap<String, Pattern>();

    public void addClassPattern(String classPattern)
    {
        StringBuffer regex = new StringBuffer();
        for (char c : classPattern.toCharArray())
        {
            if (c == '.')
            {
                regex.append("\\.");
            }
            else if (c == '*')
            {
                regex.append(".*");
            }
            else
            {
                regex.append(c);
            }
        }
        classPatterns.put(classPattern,Pattern.compile(regex.toString()));
    }

    @Override
    public String getDescription()
    {
        return "Ensures that forbidden packages are not present in the war file";
    }

    @Override
    public String getName()
    {
        return "forbidden-class";
    }

    private void validateClassname(String path, String className)
    {
        for (Map.Entry<String, Pattern> pattern : this.classPatterns.entrySet())
        {
            if (pattern.getValue().matcher(className).matches())
            {
                error(path,"Class forbidden by pattern: " + pattern.getKey());
            }
        }
    }

    @Override
    public void visitWebInfClass(String path, String className, File classFile)
    {
        validateClassname(path,className);
    }

    @Override
    public void visitArchiveClass(String path, String className, ZipFile archive, ZipEntry archiveEntry)
    {
        validateClassname(path,className);
    }
}
