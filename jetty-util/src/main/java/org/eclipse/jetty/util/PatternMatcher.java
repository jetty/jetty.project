//
//  ========================================================================
//  Copyright (c) 1995-2016 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.util;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

public abstract class PatternMatcher
{
    public abstract void matched (URI uri) throws Exception;
    
    
    /**
     * Find jar names from the provided list matching a pattern.
     * 
     * If the pattern is null and isNullInclusive is true, then
     * all jar names will match.
     * 
     * A pattern is a set of acceptable jar names. Each acceptable
     * jar name is a regex. Each regex can be separated by either a
     * "," or a "|". If you use a "|" this or's together the jar
     * name patterns. This means that ordering of the matches is
     * unimportant to you. If instead, you want to match particular
     * jar names, and you want to match them in order, you should
     * separate the regexs with "," instead. 
     * 
     * Eg "aaa-.*\\.jar|bbb-.*\\.jar"
     * Will iterate over the jar names and match
     * in any order.
     * 
     * Eg "aaa-*\\.jar,bbb-.*\\.jar"
     * Will iterate over the jar names, matching
     * all those starting with "aaa-" first, then "bbb-".
     *
     * @param pattern the pattern
     * @param uris the uris to test the pattern against
     * @param isNullInclusive if true, an empty pattern means all names match, if false, none match
     * @throws Exception if fundamental error in pattern matching
     */
    public void match (Pattern pattern, URI[] uris, boolean isNullInclusive)
    throws Exception
    {
        if (uris!=null)
        {
            String[] patterns = (pattern==null?null:pattern.pattern().split(","));

            List<Pattern> subPatterns = new ArrayList<Pattern>();
            for (int i=0; patterns!=null && i<patterns.length;i++)
            {
                subPatterns.add(Pattern.compile(patterns[i]));
            }
            if (subPatterns.isEmpty())
                subPatterns.add(pattern);

            if (subPatterns.isEmpty())
            {
                matchPatterns(null, uris, isNullInclusive);
            }
            else
            {
                //for each subpattern, iterate over all the urls, processing those that match
                for (Pattern p : subPatterns)
                {
                    matchPatterns(p, uris, isNullInclusive);
                }
            }
        }
    }


    public void matchPatterns (Pattern pattern, URI[] uris, boolean isNullInclusive)
    throws Exception
    {
        for (int i=0; i<uris.length;i++)
        {
            URI uri = uris[i];
            String s = uri.toString();
            if ((pattern == null && isNullInclusive)
                    ||
                    (pattern!=null && pattern.matcher(s).matches()))
            {
                matched(uris[i]);
            }
        }
    }
}
