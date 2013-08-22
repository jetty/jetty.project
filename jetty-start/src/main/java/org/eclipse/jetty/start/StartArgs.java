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

import static org.eclipse.jetty.start.UsageException.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The Arguments required to start Jetty.
 */
public class StartArgs
{
    // TODO: might make sense to declare this in modules/base.mod
    private static final String SERVER_MAIN = "org.eclipse.jetty.xml.XmlConfiguration.class";

    private List<String> commandLine = new ArrayList<>();
    private List<String> enabledModules = new ArrayList<>();
    private List<String> downloads = new ArrayList<>();
    private List<String> classpath = new ArrayList<>();
    private List<String> xmls = new ArrayList<>();
    private Map<String, String> properties = new HashMap<>();
    private Map<String, String> systemProperties = new HashMap<>();
    private Map<String, String> jvmArgs = new HashMap<>();

    // Should the server be run?
    private boolean run = true;
    private boolean help = false;
    private boolean stopCommand = false;
    private boolean listModules = false;
    private boolean listClasspath = false;
    private boolean listConfig = false;
    private boolean version = false;
    private boolean dryRun = false;
    private boolean exec = false;

    public StartArgs(String[] commandLineArgs)
    {
        commandLine.addAll(Arrays.asList(commandLineArgs));
    }

    public List<String> getStartCommands()
    {
        return null;
    }

    public List<String> getCommandLine()
    {
        return this.commandLine;
    }

    public void parse(TextFile file)
    {
        for (String line : file)
        {
            parse(line);
        }
    }

    public void parse(String arg)
    {
        if ("--help".equals(arg) || "-?".equals(arg))
        {
            help = true;
            run = false;
            return;
        }

        if ("--stop".equals(arg))
        {
            stopCommand = true;
            run = false;
            /*
             * int port = Integer.parseInt(_config.getProperty("STOP.PORT","-1")); String key = _config.getProperty("STOP.KEY",null); int timeout =
             * Integer.parseInt(_config.getProperty("STOP.WAIT","0")); stop(port,key,timeout);
             */
            return;
        }

        if (arg.startsWith("--download="))
        {
            downloads.add(getValue(arg));
            return;
        }

        if ("--list-classpath".equals(arg) || "--version".equals(arg) || "-v".equals(arg) || "--info".equals(arg))
        {
            listClasspath = true;
            run = false;
            return;
        }

        if ("--list-config".equals(arg))
        {
            listConfig = true;
            run = false;
            return;
        }

        if ("--dry-run".equals(arg) || "--exec-print".equals(arg))
        {
            dryRun = true;
            run = false;
            return;
        }

        if (arg.startsWith("--enable="))
        {
            String moduleName = getValue(arg);
            // TODO:
            run = false;
            return;
        }

        if (arg.startsWith("--disable="))
        {
            String moduleName = getValue(arg);
            // TODO:
            run = false;
            return;
        }

        if (arg.startsWith("MODULE="))
        {
            enabledModules.add(getValue(arg));
            return;
        }

        if (arg.startsWith("MODULES="))
        {
            for (String moduleName : getValue(arg).split(","))
            {
                if (moduleName == null)
                {
                    continue; // skip
                }
                enabledModules.add(moduleName.trim());
            }
            return;
        }
        
        // Start property (syntax similar to System property)
        if(arg.startsWith("-D"))
        {
            String[] assign = arg.substring(2).split("=",2);
//          TODO  systemProperties.add(assign[0]);
            switch (assign.length)
            {
                case 2:
                    System.setProperty(assign[0],assign[1]);
                    break;
                case 1:
                    System.setProperty(assign[0],"");
                    break;
                default:
                    break;
            }
            return;
        }
    }

    private String getValue(String arg)
    {
        int idx = arg.indexOf('=');
        if (idx == (-1))
        {
            throw new UsageException(ERR_BAD_ARG,"Argument is missing a required value: %s",arg);
        }
        String value = arg.substring(idx + 1).trim();
        if (value.length() <= 0)
        {
            throw new UsageException(ERR_BAD_ARG,"Argument is missing a required value: %s",arg);
        }
        return value;
    }

    public void parseCommandLine()
    {
        for (String line : commandLine)
        {
            parse(line);
        }
    }

    public List<String> getEnabledModules()
    {
        return this.enabledModules;
    }

    public void expandModules(BaseHome baseHome, List<Module> activeModules)
    {
        // TODO Auto-generated method stub
    }
}
