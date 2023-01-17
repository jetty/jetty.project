//
// ========================================================================
// Copyright (c) 1995-2023 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.deploy.graph;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.text.CollationKey;
import java.text.Collator;
import java.util.Comparator;
import java.util.Set;
import java.util.TreeSet;

import org.eclipse.jetty.util.IO;

/**
 * Output the Graph in GraphViz Dot format.
 */
public class GraphOutputDot
{
    private GraphOutputDot()
    {
    }

    private static final String TOPNODE = "undeployed";

    /**
     * Comparator that makes the 'undeployed' node the first node in the sort list.
     *
     * This makes the 'undeployed' node show up at the top of the generated graph.
     */
    private static class TopNodeSort implements Comparator<Node>
    {
        private Collator collator = Collator.getInstance();

        @Override
        public int compare(Node o1, Node o2)
        {
            if (o1.getName().equals(TOPNODE))
            {
                return -1;
            }

            if (o2.getName().equals(TOPNODE))
            {
                return 1;
            }

            CollationKey key1 = toKey(o1);
            CollationKey key2 = toKey(o2);
            return key1.compareTo(key2);
        }

        private CollationKey toKey(Node node)
        {
            return collator.getCollationKey(node.getName());
        }
    }

    public static void write(Graph graph, File outputFile) throws IOException
    {
        FileWriter writer = null;
        PrintWriter out = null;

        try
        {
            writer = new FileWriter(outputFile);
            out = new PrintWriter(writer);

            out.println("// Autogenerated by " + GraphOutputDot.class.getName());
            out.println("digraph Graf {");

            writeGraphDefaults(out);
            writeNodeDefaults(out);
            writeEdgeDefaults(out);

            Set<Node> nodes = new TreeSet<Node>(new TopNodeSort());
            nodes.addAll(graph.getNodes());

            for (Node node : nodes)
            {
                writeNode(out, node);
            }

            for (Edge edge : graph.getEdges())
            {
                writeEdge(out, edge);
            }

            out.println("}");
        }
        finally
        {
            IO.close(out);
            IO.close(writer);
        }
    }

    private static void writeEdge(PrintWriter out, Edge edge)
    {
        out.println();
        out.println("  // Edge");
        out.printf("  \"%s\" -> \"%s\" [%n", toId(edge.getFrom()), toId(edge.getTo()));
        out.println("    arrowtail=none,");
        out.println("    arrowhead=normal");
        out.println("  ];");
    }

    private static void writeNode(PrintWriter out, Node node)
    {
        out.println();
        out.println("  // Node");
        out.printf("  \"%s\" [%n", toId(node));
        out.printf("    label=\"%s\",%n", node.getName());
        if (node.getName().endsWith("ed"))
        {
            out.println("    color=\"#ddddff\",");
            out.println("    style=filled,");
        }
        out.println("    shape=box");
        out.println("  ];");
    }

    private static CharSequence toId(Node node)
    {
        StringBuilder buf = new StringBuilder();

        for (char c : node.getName().toCharArray())
        {
            if (Character.isLetter(c))
            {
                buf.append(c);
                continue;
            }

            if (Character.isDigit(c))
            {
                buf.append(c);
                continue;
            }

            if ((c == ' ') || (c == '-') || (c == '_'))
            {
                buf.append(c);
                continue;
            }
        }

        return buf;
    }

    private static void writeEdgeDefaults(PrintWriter out)
    {
        out.println();
        out.println("  // Edge Defaults ");
        out.println("  edge [");
        out.println("    arrowsize=\"0.8\",");
        out.println("    fontsize=\"11\"");
        out.println("  ];");
    }

    private static void writeGraphDefaults(PrintWriter out)
    {
        out.println();
        out.println("  // Graph Defaults ");
        out.println("  graph [");
        out.println("    bgcolor=\"#ffffff\",");
        out.println("    fontname=\"Helvetica\",");
        out.println("    fontsize=\"11\",");
        out.println("    label=\"Graph\",");
        out.println("    labeljust=\"l\",");
        out.println("    rankdir=\"TD\"");
        out.println("  ];");
    }

    private static void writeNodeDefaults(PrintWriter out)
    {
        out.println();
        out.println("  // Node Defaults ");
        out.println("  node [");
        out.println("    fontname=\"Helvetica\",");
        out.println("    fontsize=\"11\",");
        out.println("    shap=\"box\"");
        out.println("  ];");
    }
}
