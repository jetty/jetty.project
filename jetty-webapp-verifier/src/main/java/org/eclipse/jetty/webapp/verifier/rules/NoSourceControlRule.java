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
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.eclipse.jetty.webapp.verifier.AbstractArchiveScanningRule;

/**
 * Prevent inclusion of Source Control files & directories that might reveal hostnames, userids, and passwords to the
 * source control. (CVS, .svn/, .git/)
 */
public class NoSourceControlRule extends AbstractArchiveScanningRule
{
    class ScmName
    {
        String scm;
        String name;

        public ScmName(String scm, String name)
        {
            this.scm = scm;
            this.name = name;
        }
    }

    private static List<ScmName> scmDirNames = new ArrayList<ScmName>();
    private static List<ScmName> scmFileNames = new ArrayList<ScmName>();

    public NoSourceControlRule()
    {
        super();

        // The order of patterns is most likely to least likely

        scmDirNames.add(new ScmName("Subversion",".svn")); // Standard Unix format
        scmDirNames.add(new ScmName("Subversion","_svn")); // Alternate Windows format
        scmDirNames.add(new ScmName("CVS","CVS"));
        scmFileNames.add(new ScmName("CVS",".cvsignore"));
        scmDirNames.add(new ScmName("Git",".git"));
        scmFileNames.add(new ScmName("Git",".gitignore"));
        scmDirNames.add(new ScmName("RCS","RCS"));
        scmDirNames.add(new ScmName("SCCS","SCCS"));
        scmFileNames.add(new ScmName("Visual SourceSafe","vssver.scc"));
        scmDirNames.add(new ScmName("Arch",".arch-ids"));
        scmDirNames.add(new ScmName("Bazaar",".bzr"));
        scmFileNames.add(new ScmName("SurroundSCM",".MySCMServerInfo"));
        scmDirNames.add(new ScmName("Mercurial",".hg"));
        scmDirNames.add(new ScmName("BitKeeper","BitKeeper"));
        scmDirNames.add(new ScmName("BitKeeper","ChangeSet"));
        scmDirNames.add(new ScmName("Darcs","_darcs"));
        scmDirNames.add(new ScmName("Darcs",".darcsrepo"));
        scmFileNames.add(new ScmName("Darcs",".darcs-temp-mail"));
    }

    @Override
    public String getDescription()
    {
        return "Prevent inclusion of source control files in webapp";
    }

    @Override
    public String getName()
    {
        return "no-source-control";
    }

    @Override
    public void visitDirectoryStart(String path, File dir)
    {
        for (ScmName scmName : scmDirNames)
        {
            if (dir.getName().equalsIgnoreCase(scmName.name))
            {
                error(path,scmName.scm + " Source Control directories are not allowed");
            }
        }
    }

    @Override
    public void visitFile(String path, File dir, File file)
    {
        for (ScmName scmName : scmFileNames)
        {
            if (file.getName().equalsIgnoreCase(scmName.name))
            {
                error(path,scmName.scm + " Source Control file are not allowed");
            }
        }
    }

    @Override
    public void visitArchiveResource(String path, ZipFile zip, ZipEntry entry)
    {
        String basename = toBaseName(entry);

        if (entry.isDirectory())
        {
            for (ScmName scmName : scmDirNames)
            {
                if (basename.equalsIgnoreCase(scmName.name))
                {
                    error(path,scmName.scm + " Source Control directories are not allowed");
                }
            }
        }
        else
        {
            for (ScmName scmName : scmFileNames)
            {
                if (basename.equalsIgnoreCase(scmName.name))
                {
                    error(path,scmName.scm + " Source Control file are not allowed");
                }
            }
        }
    }

    private String toBaseName(ZipEntry entry)
    {
        String name =entry.getName();
        if (name.endsWith("/"))
        {
            name = name.substring(0,name.length() - 1);
        }

        int idx = name.lastIndexOf('/');
        if (idx >= 0)
        {
            return name.substring(idx + 1);
        }
        return name;
    }
}
