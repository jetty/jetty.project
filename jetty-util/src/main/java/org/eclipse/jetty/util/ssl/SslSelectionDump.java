//
//  ========================================================================
//  Copyright (c) 1995-2017 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.util.ssl;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.eclipse.jetty.util.component.ContainerLifeCycle;
import org.eclipse.jetty.util.component.Dumpable;

public class SslSelectionDump extends ContainerLifeCycle implements Dumpable
{
    private static class CaptionedList extends ArrayList<String> implements Dumpable
    {
        private final String caption;
        
        public CaptionedList(String caption)
        {
            this.caption = caption;
        }
        
        @Override
        public String dump()
        {
            return ContainerLifeCycle.dump(SslSelectionDump.CaptionedList.this);
        }
        
        @Override
        public void dump(Appendable out, String indent) throws IOException
        {
            out.append(caption);
            out.append(" (size=").append(Integer.toString(size())).append(")");
            out.append(System.lineSeparator());
            ContainerLifeCycle.dump(out, indent, this);
        }
    }
    
    private final String type;
    private SslSelectionDump.CaptionedList enabled = new SslSelectionDump.CaptionedList("Enabled");
    private SslSelectionDump.CaptionedList disabled = new SslSelectionDump.CaptionedList("Disabled");
    
    public SslSelectionDump(String type,
                            String[] supportedByJVM,
                            String[] enabledByJVM,
                            String[] excludedByConfig,
                            String[] includedByConfig)
    {
        this.type = type;
        addBean(enabled);
        addBean(disabled);
        
        List<String> jvmEnabled = Arrays.asList(enabledByJVM);
        
        List<Pattern> excludedPatterns = toPatternList(excludedByConfig);
        List<Pattern> includedPatterns = toPatternList(includedByConfig);
        
        for(String entry: toSortedList(supportedByJVM))
        {
            boolean isPresent = true;
    
            StringBuilder s = new StringBuilder();
            s.append(entry);
            if (!jvmEnabled.contains(entry))
            {
                if (isPresent)
                {
                    s.append(" -");
                    isPresent = false;
                }
                s.append(" JreDisabled:java.security");
            }
    
            for (Pattern pattern : excludedPatterns)
            {
                Matcher m = pattern.matcher(entry);
                if (m.matches())
                {
                    if (isPresent)
                    {
                        s.append(" -");
                        isPresent = false;
                    }
                    else
                    {
                        s.append(",");
                    }
                    s.append(" ConfigExcluded:'").append(pattern.pattern()).append('\'');
                }
            }
    
            if (!includedPatterns.isEmpty())
            {
                boolean isIncluded = false;
                for (Pattern pattern : includedPatterns)
                {
                    Matcher m = pattern.matcher(entry);
                    if (m.matches())
                    {
                        isIncluded = true;
                        break;
                    }
                }
        
                if (!isIncluded)
                {
                    if (isPresent)
                    {
                        s.append(" -");
                        isPresent = false;
                    }
                    else
                    {
                        s.append(",");
                    }
                    s.append(" ConfigIncluded:NotSpecified");
                }
            }
    
            if (isPresent)
            {
                enabled.add(s.toString());
            }
            else
            {
                disabled.add(s.toString());
            }
        }
    }
    
    private List<String> toSortedList(String[] strings)
    {
        List<String> sorted = new ArrayList<>();
        for (String entry : strings)
        {
            sorted.add(entry);
        }
        
        Collections.sort(sorted, new Comparator<String>()
        {
            @Override
            public int compare(String o1, String o2)
            {
                return o1.compareTo(o2);
            }
        });
        
        return sorted;
    }
    
    private static List<Pattern> toPatternList(String[] configs)
    {
        // Arrays.stream(configs)
        //        .map((entry) -> Pattern.compile(entry))
        //        .collect(Collectors.toList());
        List<Pattern> ret = new ArrayList<>();
        for (String entry : configs)
        {
            ret.add(Pattern.compile(entry));
        }
        return ret;
    }
    
    @Override
    public String dump()
    {
        return ContainerLifeCycle.dump(this);
    }
    
    @Override
    public void dump(Appendable out, String indent) throws IOException
    {
        dumpBeans(out, indent);
    }
    
    @Override
    protected void dumpThis(Appendable out) throws IOException
    {
        out.append(type).append(" Selections").append(System.lineSeparator());
    }
}
