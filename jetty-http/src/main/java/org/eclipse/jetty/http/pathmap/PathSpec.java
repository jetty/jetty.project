//
//  ========================================================================
//  Copyright (c) 1995-2015 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.http.pathmap;

/**
 * The base PathSpec, what all other path specs are based on
 */
public abstract class PathSpec implements Comparable<PathSpec>
{
    protected String pathSpec;
    protected PathSpecGroup group;
    protected int pathDepth;
    protected int specLength;

    @Override
    public int compareTo(PathSpec other)
    {
        // Grouping (increasing)
        int diff = this.group.ordinal() - other.group.ordinal();
        if (diff != 0)
        {
            return diff;
        }

        // Spec Length (decreasing)
        diff = other.specLength - this.specLength;
        if (diff != 0)
        {
            return diff;
        }

        // Path Spec Name (alphabetical)
        return this.pathSpec.compareTo(other.pathSpec);
    }

    @Override
    public boolean equals(Object obj)
    {
        if (this == obj)
        {
            return true;
        }
        if (obj == null)
        {
            return false;
        }
        if (getClass() != obj.getClass())
        {
            return false;
        }
        PathSpec other = (PathSpec)obj;
        if (pathSpec == null)
        {
            if (other.pathSpec != null)
            {
                return false;
            }
        }
        else if (!pathSpec.equals(other.pathSpec))
        {
            return false;
        }
        return true;
    }

    public PathSpecGroup getGroup()
    {
        return group;
    }

    /**
     * Get the number of path elements that this path spec declares.
     * <p>
     * This is used to determine longest match logic.
     * 
     * @return the depth of the path segments that this spec declares
     */
    public int getPathDepth()
    {
        return pathDepth;
    }

    /**
     * Return the portion of the path that is after the path spec.
     * 
     * @param path
     *            the path to match against
     * @return the path info portion of the string
     */
    public abstract String getPathInfo(String path);

    /**
     * Return the portion of the path that matches a path spec.
     * 
     * @param path
     *            the path to match against
     * @return the match, or null if no match at all
     */
    public abstract String getPathMatch(String path);

    /**
     * The as-provided path spec.
     * 
     * @return the as-provided path spec
     */
    public String getPathSpec()
    {
        return pathSpec;
    }

    /**
     * Get the relative path.
     * 
     * @param base
     *            the base the path is relative to
     * @param path
     *            the additional path
     * @return the base plus path with pathSpec portion removed
     */
    public abstract String getRelativePath(String base, String path);

    @Override
    public int hashCode()
    {
        final int prime = 31;
        int result = 1;
        result = (prime * result) + ((pathSpec == null)?0:pathSpec.hashCode());
        return result;
    }

    /**
     * Test to see if the provided path matches this path spec
     * 
     * @param path
     *            the path to test
     * @return true if the path matches this path spec, false otherwise
     */
    public abstract boolean matches(String path);

    @Override
    public String toString()
    {
        StringBuilder str = new StringBuilder();
        str.append(this.getClass().getSimpleName()).append("[\"");
        str.append(pathSpec);
        str.append("\",pathDepth=").append(pathDepth);
        str.append(",group=").append(group);
        str.append("]");
        return str.toString();
    }
}
