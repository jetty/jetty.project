//
// ========================================================================
// Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.maven.plugin;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;

import org.codehaus.plexus.util.xml.Xpp3Dom;
import org.eclipse.jetty.util.StringUtil;

/**
 * OverlayConfig
 * 
 * The configuration of a war overlay in a pom. Used to help determine which resources
 * from a project's dependent war should be included.
 */
public class OverlayConfig
{
    private String targetPath;
    private String groupId;
    private String artifactId;
    private String classifier;
    private List<String> includes;
    private List<String> excludes;
    private boolean skip;
    private boolean filtered;

    public OverlayConfig()
    {
    }

    public OverlayConfig(String fmt, List<String> defaultIncludes, List<String> defaultExcludes)
    {
        if (fmt == null)
            return;
        String[] atoms = StringUtil.csvSplit(fmt);
        for (int i = 0; i < atoms.length; i++)
        {
            String s = atoms[i].trim();
            switch (i)
            {
                case 0:
                {
                    if (!"".equals(s))
                        groupId = s;
                    break;
                }
                case 1:
                {
                    if (!"".equals(s))
                        artifactId = s;
                    break;
                }
                case 2:
                {
                    if (!"".equals(s))
                        classifier = s;
                    break;
                }
                case 3:
                {
                    if (!"".equals(s))
                        targetPath = s;
                    break;
                }
                case 4:
                {
                    if ("".equals(s))
                        skip = false;
                    else
                        skip = Boolean.valueOf(s);
                    break;
                }
                case 5:
                {
                    if ("".equals(s))
                        filtered = false;
                    else
                        filtered = Boolean.valueOf(s);
                    break;
                }
                case 6:
                {
                    if ("".equals(s))
                        break;
                    String[] incs = s.split(";");
                    if (incs.length > 0)
                        includes = Arrays.asList(incs);
                    break;
                }
                case 7:
                {
                    if ("".equals(s))
                        break;
                    String[] exs = s.split(";");
                    if (exs.length > 0)
                        excludes = Arrays.asList(exs);
                    break;
                }
                default:
                    break;
            }
        }
    }

    public OverlayConfig(Xpp3Dom root, List<String> defaultIncludes, List<String> defaultExcludes)
    {
        Xpp3Dom node = root.getChild("groupId");
        setGroupId(node == null ? null : node.getValue());

        node = root.getChild("artifactId");
        setArtifactId(node == null ? null : node.getValue());

        node = root.getChild("classifier");
        setClassifier(node == null ? null : node.getValue());

        node = root.getChild("targetPath");
        setTargetPath(node == null ? null : node.getValue());

        node = root.getChild("skip");
        setSkip(node == null ? false : Boolean.valueOf(node.getValue()));

        node = root.getChild("filtered");
        setFiltered(node == null ? false : Boolean.valueOf(node.getValue()));

        node = root.getChild("includes");
        List<String> includes = null;
        if (node != null && node.getChildCount() > 0)
        {
            Xpp3Dom[] list = node.getChildren("include");
            for (int j = 0; list != null && j < list.length; j++)
            {
                if (includes == null)
                    includes = new ArrayList<>();
                includes.add(list[j].getValue());
            }
        }
        if (includes == null && defaultIncludes != null)
        {
            includes = new ArrayList<>();
            includes.addAll(defaultIncludes);
        }
        setIncludes(includes);

        node = root.getChild("excludes");
        List<String> excludes = null;
        if (node != null && node.getChildCount() > 0)
        {
            Xpp3Dom[] list = node.getChildren("exclude");
            for (int j = 0; list != null && j < list.length; j++)
            {
                if (excludes == null)
                    excludes = new ArrayList<>();
                excludes.add(list[j].getValue());
            }
        }
        if (excludes == null && defaultExcludes != null)
        {
            excludes = new ArrayList<>();
            excludes.addAll(defaultExcludes);
        }
        setExcludes(excludes);
    }

    public String getTargetPath()
    {
        return targetPath;
    }

    public void setTargetPath(String targetPath)
    {
        this.targetPath = targetPath;
    }

    public String getGroupId()
    {
        return groupId;
    }

    public void setGroupId(String groupId)
    {
        this.groupId = groupId;
    }

    public String getArtifactId()
    {
        return artifactId;
    }

    public void setArtifactId(String artifactId)
    {
        this.artifactId = artifactId;
    }

    public String getClassifier()
    {
        return classifier;
    }

    public void setClassifier(String classifier)
    {
        this.classifier = classifier;
    }

    public List<String> getIncludes()
    {
        return includes;
    }

    public void setIncludes(List<String> includes)
    {
        this.includes = includes;
    }

    public List<String> getExcludes()
    {
        return excludes;
    }

    public void setExcludes(List<String> excludes)
    {
        this.excludes = excludes;
    }

    public boolean isSkip()
    {
        return skip;
    }

    public void setSkip(boolean skip)
    {
        this.skip = skip;
    }

    public boolean isFiltered()
    {
        return filtered;
    }

    public void setFiltered(boolean filtered)
    {
        this.filtered = filtered;
    }

    public boolean isCurrentProject()
    {
        if (this.groupId == null && this.artifactId == null)
            return true;
        return false;
    }

    /**
     * Check if this overlay configuration matches an Artifact's info
     *
     * @param gid Artifact groupId
     * @param aid Artifact artifactId
     * @param cls Artifact classifier
     * @return true if matched
     */
    public boolean matchesArtifact(String gid, String aid, String cls)
    {
        if (((getGroupId() == null && gid == null) || (getGroupId() != null && getGroupId().equals(gid))) &&
            ((getArtifactId() == null && aid == null) || (getArtifactId() != null && getArtifactId().equals(aid))) &&
            ((getClassifier() == null) || (getClassifier().equals(cls))))
            return true;

        return false;
    }

    /**
     * Check if this overlay configuration matches an Artifact's info
     *
     * @param gid the group id
     * @param aid the artifact id
     * @return true if matched
     */
    public boolean matchesArtifact(String gid, String aid)
    {
        if (((getGroupId() == null && gid == null) || (getGroupId() != null && getGroupId().equals(gid))) &&
            ((getArtifactId() == null && aid == null) || (getArtifactId() != null && getArtifactId().equals(aid))))
            return true;

        return false;
    }

    @Override
    public String toString()
    {
        StringBuilder strbuff = new StringBuilder();
        strbuff.append((groupId != null ? groupId : "") + ",");
        strbuff.append((artifactId != null ? artifactId : "") + ",");
        strbuff.append((classifier != null ? classifier : "") + ",");
        strbuff.append((targetPath != null ? targetPath : "") + ",");
        strbuff.append("" + skip + ",");
        strbuff.append("" + filtered + ",");

        if (includes != null)
        {
            Iterator<String> itor = includes.iterator();
            while (itor.hasNext())
            {
                strbuff.append(itor.next());
                if (itor.hasNext())
                    strbuff.append(";");
            }
        }

        strbuff.append(", ");

        if (excludes != null)
        {
            Iterator<String> itor = excludes.iterator();
            while (itor.hasNext())
            {
                strbuff.append(itor.next());
                if (itor.hasNext())
                    strbuff.append(";");
            }
        }

        return strbuff.toString();
    }
}

