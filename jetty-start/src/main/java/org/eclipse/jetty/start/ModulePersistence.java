//
//  ========================================================================
//  Copyright (c) 1995-2013 Mort Bay Consulting Pty. Ltd.
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
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.ListIterator;
import java.util.Set;

/**
 * Represents the optional, managed by jetty command line, file at ${jetty.base}/modules/enabled
 */
public class ModulePersistence extends TextFile
{
    public ModulePersistence(File file) throws FileNotFoundException, IOException
    {
        super(file);
    }

    public boolean disableModule(StartArgs args, String moduleName) throws IOException
    {
        List<String> modulesThatWereDisabled = new ArrayList<>();
        Set<String> resolvedModulesToDisable = args.getAllModules().resolveChildModulesOf(moduleName);

        // Show user what could be disabled
        System.out.printf(" - Disabling Module: %s%n",moduleName);
        if (resolvedModulesToDisable.size() > 1)
        {
            System.out.printf(" - (Resolved to) : ");
            boolean needDelim = false;
            for (String name : resolvedModulesToDisable)
            {
                if (needDelim)
                {
                    System.out.print(" -> ");
                }
                System.out.print(name);
                needDelim = true;
            }
            System.out.println();
        }

        // Do the disabling
        ListIterator<String> iter = super.listIterator();
        while (iter.hasNext())
        {
            String line = iter.next().trim();
            if (resolvedModulesToDisable.contains(line))
            {
                iter.remove();
                modulesThatWereDisabled.add(line);
            }
        }

        // Save file
        saveFile();

        // Show user what was disabled
        if (modulesThatWereDisabled.size() > 0)
        {
            System.out.printf("Disabled %d module%s%n",modulesThatWereDisabled.size(),modulesThatWereDisabled.size() > 1?"s":"");
            for (String name : modulesThatWereDisabled)
            {
                System.out.printf(" - %s%n",name);
            }
            return true;
        }
        else
        {
            System.out.printf("Module %s not found, nothing actually disabled.%n",moduleName);
            return false;
        }
    }

    public boolean enableModule(StartArgs args, String moduleName) throws IOException
    {
        boolean ret = false;
        System.out.printf(" - Enabling Module: %s%n",moduleName);
        if (getLines().contains(moduleName))
        {
            // duplicate
            System.out.printf(" - Already present, not adding again%n");
        }
        else
        {
            // add it
            getLines().add(moduleName);
            System.out.printf(" - Adding module %s%n",moduleName);
            Set<String> transitiveNames = args.getAllModules().resolveParentModulesOf(moduleName);
            if (transitiveNames.size() > 1)
            {
                System.out.print(" - Enabled: ");
                boolean needDelim = false;
                for (String name : transitiveNames)
                {
                    if (needDelim)
                    {
                        System.out.print(" -> ");
                    }
                    System.out.print(name);
                    needDelim = true;
                }
                System.out.println();
            }
            saveFile();
        }
        return ret;
    }

    public List<String> getEnabled()
    {
        return getLines();
    }

    private void saveFile() throws IOException
    {
        File file = getFile();
        File parent = file.getParentFile();
        FS.ensureDirectoryExists(parent);
        
        try (FileWriter writer = new FileWriter(file,false))
        {
            for (String line : getLines())
            {
                writer.append(line).append('\n');
            }
        }
    }
}
