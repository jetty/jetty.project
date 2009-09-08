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
package org.eclipse.jetty.webapp.verifier.support;

public class PathGlob
{
    /**
     * @return true if match.
     */
    public static boolean match(String pathSpec, String path) throws IllegalArgumentException
    {
        return match(pathSpec,path,false);
    }

    /**
     * @return true if match.
     */
    public static boolean match(String pathSpec, String path, boolean noDefault) throws IllegalArgumentException
    {
        char c = pathSpec.charAt(0);
        if (c == '/')
        {
            if (!noDefault && pathSpec.length() == 1 || pathSpec.equals(path))
                return true;

            if (isPathWildcardMatch(pathSpec,path))
                return true;
        }
        else if (c == '*')
            return path.regionMatches(path.length() - pathSpec.length() + 1,pathSpec,1,pathSpec.length() - 1);
        return false;
    }

    private static boolean isPathWildcardMatch(String pathSpec, String path)
    {
        // For a spec of "/foo/*" match "/foo" , "/foo/..." but not "/foobar"
        int cpl = pathSpec.length() - 2;
        if (pathSpec.endsWith("/*") && path.regionMatches(0,pathSpec,0,cpl))
        {
            if (path.length() == cpl || '/' == path.charAt(cpl))
                return true;
        }
        return false;
    }
}
