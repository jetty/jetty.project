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
import java.util.Enumeration;
import java.util.List;
import java.util.jar.JarFile;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.eclipse.jetty.webapp.verifier.AbstractRule;

/**
 * NoScriptingRule ensure that no scripting languages are used in the webapp.
 * 
 * Checks for actual script files in webapp filesystem, and existance of popular scripting language libraries as well.
 */
public class NoScriptingRule extends AbstractRule
{
    class Forbidden
    {
        public String key;
        public String msg;

        public Forbidden(String key, String msg)
        {
            super();
            this.key = key;
            this.msg = msg;
        }
    }

    private boolean allowJRuby = false;
    private boolean allowBeanshell = false;
    private boolean allowGroovy = false;
    private boolean allowJython = false;
    private boolean allowShell = false;
    private List<Forbidden> forbiddenFileExtensions = new ArrayList<Forbidden>();
    private List<Forbidden> forbiddenClassIds = new ArrayList<Forbidden>();

    public boolean isAllowShell()
    {
        return allowShell;
    }

    public void setAllowShell(boolean allowShell)
    {
        this.allowShell = allowShell;
    }

    public boolean isAllowJRuby()
    {
        return allowJRuby;
    }

    public void setAllowJRuby(boolean allowJruby)
    {
        this.allowJRuby = allowJruby;
    }

    public boolean isAllowBeanshell()
    {
        return allowBeanshell;
    }

    public void setAllowBeanshell(boolean allowBeanshell)
    {
        this.allowBeanshell = allowBeanshell;
    }

    public boolean isAllowGroovy()
    {
        return allowGroovy;
    }

    public void setAllowGroovy(boolean allowGroovy)
    {
        this.allowGroovy = allowGroovy;
    }

    public boolean isAllowJython()
    {
        return allowJython;
    }

    public void setAllowJython(boolean allowJython)
    {
        this.allowJython = allowJython;
    }

    public String getDescription()
    {
        return "Do not allow scripting languages in webapp";
    }

    public String getName()
    {
        return "forbidden-scripting";
    }

    @Override
    public void initialize() throws Throwable
    {
        forbiddenFileExtensions.clear();
        forbiddenClassIds.clear();
        if (!allowJRuby)
        {
            String msg = "JRuby scripting not allowed";
            forbiddenFileExtensions.add(new Forbidden(".rb",msg));
            forbiddenFileExtensions.add(new Forbidden(".rhtml",msg));
            msg = "JRuby dependencies are not allowed";
            forbiddenClassIds.add(new Forbidden(".jruby.",msg));
        }

        if (!allowJython)
        {
            String msg = "Jython and Python scripting not allowed";
            forbiddenFileExtensions.add(new Forbidden(".py",msg));
            forbiddenFileExtensions.add(new Forbidden(".pyc",msg));
            msg = "Jython dependencies are not allowed";
            forbiddenClassIds.add(new Forbidden("org.python.",msg));
        }

        if (!allowGroovy)
        {
            String msg = "Groovy scripting not allowed";
            forbiddenFileExtensions.add(new Forbidden(".groovy",msg));
            msg = "Groovy dependencies are not allowed";
            forbiddenClassIds.add(new Forbidden(".groovy.",msg));
        }

        if (!allowShell)
        {
            String msg = "Shell scripting not allowed";
            forbiddenFileExtensions.add(new Forbidden(".sh",msg));
            forbiddenFileExtensions.add(new Forbidden(".bat",msg));
            forbiddenFileExtensions.add(new Forbidden(".cmd",msg));
            forbiddenFileExtensions.add(new Forbidden(".vbs",msg));
        }
    }

    @Override
    public void visitFile(String path, File dir, File file)
    {
        String name = file.getName().toLowerCase();
        for (Forbidden forbidden : forbiddenFileExtensions)
        {
            if (name.endsWith(forbidden.key))
            {
                error(path,forbidden.msg);
            }
        }
    }

    @Override
    public void visitWebInfClass(String path, String className, File classFile)
    {
        validateClassname(path,className);
    }

    private void validateClassname(String path, String className)
    {
        for (Forbidden forbidden : forbiddenClassIds)
        {
            if (className.contains(forbidden.key))
            {
                error(path,forbidden.msg);
            }
        }
    }

    @Override
    public void visitWebInfClassResource(String path, String resourcePath, File resourceFile)
    {
        super.visitWebInfClassResource(path,resourcePath,resourceFile);
    }

    @Override
    public void visitWebInfLibJar(String path, File archive, JarFile jar)
    {
        iterateArchive(path,archive,jar);
    }

    private void iterateArchive(String path, File archive, ZipFile zip)
    {
        try
        {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements())
            {
                ZipEntry entry = entries.nextElement();
                if (entry.getName().endsWith(".class"))
                {
                    checkArchiveClassname(path,archive,entry.getName());
                }
            }
        }
        catch (Throwable t)
        {
            exception(path,"Unable to iterate archive: Contents invalid?: " + t.getMessage(),t);
        }
    }

    private void checkArchiveClassname(String path, File archive, String name)
    {
        String className = name.replace("/",".");
        if (className.endsWith(".class"))
        {
            className = className.substring(0,className.length() - 6);
        }

        validateClassname(path + "!/" + name,className);
    }

    @Override
    public void visitWebInfLibZip(String path, File archive, ZipFile zip)
    {
        iterateArchive(path,archive,zip);
    }
}
