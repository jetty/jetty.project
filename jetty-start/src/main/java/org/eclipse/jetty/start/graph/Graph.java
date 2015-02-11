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

package org.eclipse.jetty.start.graph;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Stack;

import org.eclipse.jetty.start.Props;
import org.eclipse.jetty.start.StartLog;
import org.eclipse.jetty.start.Utils;

/**
 * Basic Graph
 */
public abstract class Graph<T extends Node<T>> implements Iterable<T>
{
    private String selectionTerm = "select";
    private String nodeTerm = "node";
    private Map<String, T> nodes = new LinkedHashMap<>();
    private int maxDepth = -1;

    protected Set<String> asNameSet(Set<T> nodeSet)
    {
        Set<String> ret = new HashSet<>();
        for (T node : nodeSet)
        {
            ret.add(node.getName());
        }
        return ret;
    }

    private void assertNoCycle(T node, Stack<String> refs)
    {
        for (T parent : node.getParentEdges())
        {
            if (refs.contains(parent.getName()))
            {
                // Cycle detected.
                StringBuilder err = new StringBuilder();
                err.append("A cyclic reference in the ");
                err.append(this.getClass().getSimpleName());
                err.append(" has been detected: ");
                for (int i = 0; i < refs.size(); i++)
                {
                    if (i > 0)
                    {
                        err.append(" -> ");
                    }
                    err.append(refs.get(i));
                }
                err.append(" -> ").append(parent.getName());
                throw new IllegalStateException(err.toString());
            }

            refs.push(parent.getName());
            assertNoCycle(parent,refs);
            refs.pop();
        }
    }

    private void bfsCalculateDepth(final T node, final int depthNow)
    {
        int depth = depthNow + 1;

        // Set depth on every child first
        for (T child : node.getChildEdges())
        {
            child.setDepth(Math.max(depth,child.getDepth()));
            this.maxDepth = Math.max(this.maxDepth,child.getDepth());
        }

        // Dive down
        for (T child : node.getChildEdges())
        {
            bfsCalculateDepth(child,depth);
        }
    }

    public void buildGraph() throws FileNotFoundException, IOException
    {
        // Connect edges
        // Make a copy of nodes.values() as the list could be modified
        List<T> nodeList = new ArrayList<>(nodes.values());
        for (T node : nodeList)
        {
            for (String parentName : node.getParentNames())
            {
                T parent = get(parentName);

                if (parent == null)
                {
                    parent = resolveNode(parentName);
                }

                if (parent == null)
                {
                    if (Props.hasPropertyKey(parentName))
                    {
                        StartLog.debug("Module property not expandable (yet) [%s]",parentName);
                    }
                    else
                    {
                        StartLog.warn("Module not found [%s]",parentName);
                    }
                }
                else
                {
                    node.addParentEdge(parent);
                    parent.addChildEdge(node);
                }
            }

            for (String optionalParentName : node.getOptionalParentNames())
            {
                T optional = get(optionalParentName);
                if (optional == null)
                {
                    StartLog.debug("Optional module not found [%s]",optionalParentName);
                }
                else if (optional.isSelected())
                {
                    node.addParentEdge(optional);
                    optional.addChildEdge(node);
                }
            }
        }

        // Verify there is no cyclic references
        Stack<String> refs = new Stack<>();
        for (T module : nodes.values())
        {
            refs.push(module.getName());
            assertNoCycle(module,refs);
            refs.pop();
        }

        // Calculate depth of all modules for sorting later
        for (T module : nodes.values())
        {
            if (module.getParentEdges().isEmpty())
            {
                bfsCalculateDepth(module,0);
            }
        }
    }

    public boolean containsNode(String name)
    {
        return nodes.containsKey(name);
    }

    public int count()
    {
        return nodes.size();
    }

    public void dumpSelectedTree()
    {
        List<T> ordered = new ArrayList<>();
        ordered.addAll(nodes.values());
        Collections.sort(ordered,new NodeDepthComparator());

        List<T> active = getSelected();

        for (T module : ordered)
        {
            if (active.contains(module))
            {
                // Show module name
                String indent = toIndent(module.getDepth());
                boolean transitive = module.matches(OnlyTransitivePredicate.INSTANCE);
                System.out.printf("%s + %s: %s [%s]%n",indent,toCap(nodeTerm),module.getName(),transitive?"transitive":"selected");
            }
        }
    }

    public void dumpSelected()
    {
        List<T> ordered = new ArrayList<>();
        ordered.addAll(nodes.values());
        Collections.sort(ordered,new NodeDepthComparator());

        List<T> active = getSelected();

        for (T module : ordered)
        {
            if (active.contains(module))
            {
                // Show module name
                boolean transitive = module.matches(OnlyTransitivePredicate.INSTANCE);
                System.out.printf("  %3d) %-15s ",module.getDepth() + 1,module.getName());
                if (transitive)
                {
                    System.out.println("<transitive> ");
                }
                else
                {
                    List<String> hows = new ArrayList<>();
                    for (Selection selection : module.getSelections())
                    {
                        if (selection.isExplicit())
                        {
                            hows.add(selection.getHow());
                        }
                    }
                    Collections.sort(hows);
                    System.out.println(Utils.join(hows,", "));
                }
            }
        }
    }

    protected void findChildren(T module, Set<T> ret)
    {
        ret.add(module);
        for (T child : module.getChildEdges())
        {
            ret.add(child);
        }
    }

    protected void findParents(T module, Map<String, T> ret)
    {
        ret.put(module.getName(),module);
        for (T parent : module.getParentEdges())
        {
            ret.put(parent.getName(),parent);
            findParents(parent,ret);
        }
    }

    public T get(String name)
    {
        return nodes.get(name);
    }

    /**
     * Get the list of Selected nodes.
     */
    public List<T> getSelected()
    {
        return getMatching(new AnySelectionPredicate());
    }

    /**
     * Get the Nodes from the tree that match the provided predicate.
     *
     * @param predicate
     *            the way to match nodes
     * @return the list of matching nodes in execution order.
     */
    public List<T> getMatching(Predicate predicate)
    {
        List<T> selected = new ArrayList<T>();

        for (T node : nodes.values())
        {
            if (predicate.match(node))
            {
                selected.add(node);
            }
        }

        Collections.sort(selected,new NodeDepthComparator());
        return selected;
    }

    public int getMaxDepth()
    {
        return maxDepth;
    }

    public Set<T> getModulesAtDepth(int depth)
    {
        Set<T> ret = new HashSet<>();
        for (T node : nodes.values())
        {
            if (node.getDepth() == depth)
            {
                ret.add(node);
            }
        }
        return ret;
    }

    public Collection<String> getNodeNames()
    {
        return nodes.keySet();
    }

    public Collection<T> getNodes()
    {
        return nodes.values();
    }

    public String getNodeTerm()
    {
        return nodeTerm;
    }

    public String getSelectionTerm()
    {
        return selectionTerm;
    }

    @Override
    public Iterator<T> iterator()
    {
        return nodes.values().iterator();
    }

    public abstract void onNodeSelected(T node);

    public T register(T node)
    {
        StartLog.debug("Registering Node: [%s] %s",node.getName(),node);
        nodes.put(node.getName(),node);
        return node;
    }

    public Set<String> resolveChildNodesOf(String nodeName)
    {
        Set<T> ret = new HashSet<>();
        T module = get(nodeName);
        findChildren(module,ret);
        return asNameSet(ret);
    }

    /**
     * Resolve a node just in time.
     * <p>
     * Useful for nodes that are virtual/transient in nature (such as the jsp/jstl/alpn modules)
     */
    public abstract T resolveNode(String name);

    public Set<String> resolveParentModulesOf(String nodeName)
    {
        Map<String, T> ret = new HashMap<>();
        T node = get(nodeName);
        findParents(node,ret);
        return ret.keySet();
    }

    public int selectNode(Predicate nodePredicate, Selection selection)
    {
        int count = 0;
        List<T> matches = getMatching(nodePredicate);
        if (matches.isEmpty())
        {
            StringBuilder err = new StringBuilder();
            err.append("WARNING: Cannot ").append(selectionTerm);
            err.append(" requested ").append(nodeTerm);
            err.append("s.  ").append(nodePredicate);
            err.append(" returned no matches.");
            StartLog.warn(err.toString());
            return count;
        }

        // select them
        for (T node : matches)
        {
            count += selectNode(node,selection);
        }

        return count;
    }

    public int selectNode(String name, Selection selection)
    {
        int count = 0;
        T node = get(name);
        if (node == null)
        {
            StringBuilder err = new StringBuilder();
            err.append("Cannot ").append(selectionTerm);
            err.append(" requested ").append(nodeTerm);
            err.append(" [").append(name).append("]: not a valid ");
            err.append(nodeTerm).append(" name.");
            StartLog.warn(err.toString());
            return count;
        }

        count += selectNode(node,selection);

        return count;
    }

    private int selectNode(T node, Selection selection)
    {
        int count = 0;

        if (node.getSelections().contains(selection))
        {
            // Already enabled with this selection.
            return count;
        }

        StartLog.debug("%s %s: %s (via %s)",toCap(selectionTerm),nodeTerm,node.getName(),selection);

        boolean newlySelected = node.getSelections().isEmpty();

        // Add self
        node.addSelection(selection);
        if (newlySelected)
        {
            onNodeSelected(node);
        }
        count++;

        // Walk transitive
        Selection transitive = selection.asTransitive();
        List<String> parentNames = new ArrayList<>();
        parentNames.addAll(node.getParentNames());

        count += selectNodes(parentNames,transitive);

        return count;
    }

    public int selectNodes(Collection<String> names, Selection selection)
    {
        StartLog.debug("%s [%s] (via %s)",toCap(selectionTerm),Utils.join(names,", "),selection);

        int count = 0;

        for (String name : names)
        {
            T node = get(name);
            // Node doesn't exist yet (try to resolve it it just-in-time)
            if (node == null)
            {
                StartLog.debug("resolving node [%s]",name);
                node = resolveNode(name);
            }
            // Node still doesn't exist? this is now an invalid graph.
            if (node == null)
            {
                throw new GraphException("Missing referenced dependency: " + name);
            }

            count += selectNode(node.getName(),selection);
        }

        return count;
    }

    public void setNodeTerm(String nodeTerm)
    {
        this.nodeTerm = nodeTerm;
    }

    public void setSelectionTerm(String selectionTerm)
    {
        this.selectionTerm = selectionTerm;
    }

    private String toCap(String str)
    {
        StringBuilder cap = new StringBuilder();
        cap.append(Character.toUpperCase(str.charAt(0)));
        cap.append(str.substring(1));
        return cap.toString();
    }

    private String toIndent(int depth)
    {
        char indent[] = new char[depth * 2];
        Arrays.fill(indent,' ');
        return new String(indent);
    }
}
