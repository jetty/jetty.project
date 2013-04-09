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

package org.eclipse.jetty.websocket.jsr356.server.pathmap;

import java.util.regex.Pattern;

public class ServletPathSpec extends PathSpec
{
    public static final String PATH_SPEC_SEPARATORS = ":,";

    /**
     * Get multi-path spec splits.
     * 
     * @param servletPathSpec
     *            the path spec that might contain multiple declared path specs
     * @return the individual path specs found.
     */
    public static ServletPathSpec[] getMultiPathSpecs(String servletPathSpec)
    {
        String pathSpecs[] = servletPathSpec.split(PATH_SPEC_SEPARATORS);
        int len = pathSpecs.length;
        ServletPathSpec sps[] = new ServletPathSpec[len];
        for (int i = 0; i < len; i++)
        {
            sps[i] = new ServletPathSpec(pathSpecs[i]);
        }
        return sps;
    }

    public ServletPathSpec(String servletPathSpec)
    {
        super();
        assertValidServletPathSpec(servletPathSpec);

        // The Path Spec for Default Servlet
        if ((servletPathSpec == null) || (servletPathSpec.length() == 0) || "/".equals(servletPathSpec))
        {
            super.pathSpec = "/";
            super.pattern = Pattern.compile("^(/.*)$");
            super.pathDepth = -1; // force this to be last in sort order
            this.specLength = 1;
            this.group = PathSpecGroup.DEFAULT;
            return;
        }

        StringBuilder regex = new StringBuilder();
        regex.append("^");
        this.specLength = servletPathSpec.length();
        super.pathDepth = 0;
        char lastChar = servletPathSpec.charAt(specLength - 1);
        // prefix based
        if ((servletPathSpec.charAt(0) == '/') && (specLength > 1) && (lastChar == '*'))
        {
            this.group = PathSpecGroup.PREFIX_GLOB;
        }
        // suffix based
        else if (servletPathSpec.charAt(0) == '*')
        {
            this.group = PathSpecGroup.SUFFIX_GLOB;
        }
        else
        {
            this.group = PathSpecGroup.EXACT;
        }

        for (int i = 0; i < specLength; i++)
        {
            int cp = servletPathSpec.codePointAt(i);
            if (cp >= 128)
            {
                regex.appendCodePoint(cp);
            }
            else
            {
                char c = (char)cp;
                switch (c)
                {
                    case '*':
                        if (group != PathSpecGroup.PREFIX_GLOB)
                        {
                            regex.append(".*");
                        }
                        break;
                    case '.':
                        regex.append("\\.");
                        break;
                    case '/':
                        super.pathDepth++;
                        if ((group == PathSpecGroup.PREFIX_GLOB) && (i == (specLength - 2)))
                        {
                            regex.append("(/.*)?");
                        }
                        else
                        {
                            regex.append('/');
                        }
                        break;
                    default:
                        regex.append(c);
                        break;
                }
            }
        }

        if ((group == PathSpecGroup.EXACT) && (lastChar != '/'))
        {
            super.pathDepth++;
            regex.append("/?$");
        }
        else
        {
            regex.append('$');
        }

        super.pathSpec = servletPathSpec;
        super.pattern = Pattern.compile(regex.toString());
    }

    private void assertValidServletPathSpec(String servletPathSpec)
    {
        if ((servletPathSpec == null) || servletPathSpec.equals(""))
        {
            return; // empty path spec
        }

        // Ensure we don't have path spec separators here in our single path spec.
        for (char c : PATH_SPEC_SEPARATORS.toCharArray())
        {
            if (servletPathSpec.indexOf(c) >= 0)
            {
                throw new IllegalArgumentException("Servlet Spec 12.2 violation: encountered Path Spec Separator [" + PATH_SPEC_SEPARATORS
                        + "] within specified path spec. did you forget to split this path spec up?");
            }
        }

        int len = servletPathSpec.length();
        // path spec must either start with '/' or '*.'
        if (servletPathSpec.charAt(0) == '/')
        {
            // Prefix Based
            if (len == 1)
            {
                return; // simple '/' path spec
            }
            int idx = servletPathSpec.indexOf('*');
            if (idx < 0)
            {
                return; // no hit on glob '*'
            }
            // only allowed to have '*' at the end of the path spec
            if (idx != (len - 1))
            {
                throw new IllegalArgumentException("Servlet Spec 12.2 violation: glob '*' can only exist at end of prefix based matches");
            }
        }
        else if (servletPathSpec.startsWith("*."))
        {
            // Suffix Based
            int idx = servletPathSpec.indexOf('/');
            // cannot have path separator
            if (idx >= 0)
            {
                throw new IllegalArgumentException("Servlet Spec 12.2 violation: suffix based path spec cannot have path separators");
            }

            idx = servletPathSpec.indexOf('*',2);
            // only allowed to have 1 glob '*', at the start of the path spec
            if (idx >= 1)
            {
                throw new IllegalArgumentException("Servlet Spec 12.2 violation: suffix based path spec cannot have multiple glob '*'");
            }
        }
        else
        {
            throw new IllegalArgumentException("Servlet Spec 12.2 violation: path spec must start with \"/\" or \"*.\"");
        }
    }
}
