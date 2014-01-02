//
//  ========================================================================
//  Copyright (c) 1995-2014 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.webapp;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import org.eclipse.jetty.util.resource.Resource;


/**
 * Ordering
 *
 * Ordering options for jars in WEB-INF lib.
 */
public interface Ordering
{  

    public List<Resource> order(List<Resource> fragments);
    public boolean isAbsolute ();
    public boolean hasOther();


    /**
     * AbsoluteOrdering
     *
     * An &lt;absolute-order&gt; element in web.xml
     */
    public static class AbsoluteOrdering implements Ordering
    {
        public static final String OTHER = "@@-OTHER-@@";
        protected List<String> _order = new ArrayList<String>();
        protected boolean _hasOther = false;
        protected MetaData _metaData;
    
        public AbsoluteOrdering (MetaData metaData)
        {
            _metaData = metaData;
        }
        
        /** 
         * Order the list of jars in WEB-INF/lib according to the ordering declarations in the descriptors
         * @see org.eclipse.jetty.webapp.Ordering#order(java.util.List)
         */
        @Override
        public List<Resource> order(List<Resource> jars)
        {           
            List<Resource> orderedList = new ArrayList<Resource>();
            List<Resource> tmp = new ArrayList<Resource>(jars);
          
            //1. put everything into the list of named others, and take the named ones out of there,
            //assuming we will want to use the <other> clause
            Map<String,FragmentDescriptor> others = new HashMap<String,FragmentDescriptor>(_metaData.getNamedFragments());
            
            //2. for each name, take out of the list of others, add to tail of list
            int index = -1;
            for (String item:_order)
            {
                if (!item.equals(OTHER))
                {
                    FragmentDescriptor f = others.remove(item);
                    if (f != null)
                    {
                        Resource jar = _metaData.getJarForFragment(item);
                        orderedList.add(jar); //take from others and put into final list in order, ignoring duplicate names
                        //remove resource from list for resource matching name of descriptor
                        tmp.remove(jar);
                    }
                }
                else
                    index = orderedList.size(); //remember the index at which we want to add in all the others
            }
            
            //3. if <other> was specified, insert rest of the fragments 
            if (_hasOther)
            {
                orderedList.addAll((index < 0? 0: index), tmp);
            }
            
            return orderedList;
        }
        
        @Override
        public boolean isAbsolute()
        {
            return true;
        }
        
        public void add (String name)
        {
            _order.add(name); 
        }
        
        public void addOthers ()
        {
            if (_hasOther)
                throw new IllegalStateException ("Duplicate <other> element in absolute ordering");
            
            _hasOther = true;
            _order.add(OTHER);
        }
        
        @Override
        public boolean hasOther ()
        {
            return _hasOther;
        }
    }
    /**
     * RelativeOrdering
     *
     * A set of &lt;order&gt; elements in web-fragment.xmls.
     */
    public static class RelativeOrdering implements Ordering
    {
        protected MetaData _metaData;
        protected LinkedList<Resource> _beforeOthers = new LinkedList<Resource>();
        protected LinkedList<Resource> _afterOthers = new LinkedList<Resource>();
        protected LinkedList<Resource> _noOthers = new LinkedList<Resource>();
        
        public RelativeOrdering (MetaData metaData)
        {
            _metaData = metaData;
        }
        /** 
         * Order the list of jars according to the ordering declared
         * in the various web-fragment.xml files.
         * @see org.eclipse.jetty.webapp.Ordering#order(java.util.List)
         */
        @Override
        public List<Resource> order(List<Resource> jars)
        {         
            //for each jar, put it into the ordering according to the fragment ordering
            for (Resource jar:jars)
            {
                //check if the jar has a fragment descriptor
                FragmentDescriptor descriptor = _metaData.getFragment(jar);
                if (descriptor != null)
                {
                    switch (descriptor.getOtherType())
                    {
                        case None:
                        {
                            ((RelativeOrdering)_metaData.getOrdering()).addNoOthers(jar);
                            break;
                        }
                        case Before:
                        { 
                            ((RelativeOrdering)_metaData.getOrdering()).addBeforeOthers(jar);
                            break;
                        }
                        case After:
                        {
                            ((RelativeOrdering)_metaData.getOrdering()).addAfterOthers(jar);
                            break;
                        }
                    } 
                }
                else
                {
                    //jar fragment has no descriptor, but there is a relative ordering in place, so it must be part of the others
                    ((RelativeOrdering)_metaData.getOrdering()).addNoOthers(jar);
                }
            }            
                
            //now apply the ordering
            List<Resource> orderedList = new ArrayList<Resource>(); 
            int maxIterations = 2;
            boolean done = false;
            do
            {
                //1. order the before-others according to any explicit before/after relationships 
                boolean changesBefore = orderList(_beforeOthers);
    
                //2. order the after-others according to any explicit before/after relationships
                boolean changesAfter = orderList(_afterOthers);
    
                //3. order the no-others according to their explicit before/after relationships
                boolean changesNone = orderList(_noOthers);
                
                //we're finished on a clean pass through with no ordering changes
                done = (!changesBefore && !changesAfter && !changesNone);
            }
            while (!done && (--maxIterations >0));
            
            //4. merge before-others + no-others +after-others
            if (!done)
                throw new IllegalStateException("Circular references for fragments");
            
            for (Resource r: _beforeOthers)
                orderedList.add(r);
            for (Resource r: _noOthers)
                orderedList.add(r);
            for(Resource r: _afterOthers)
                orderedList.add(r);
            
            return orderedList;
        }
        
        @Override
        public boolean isAbsolute ()
        {
            return false;
        }
        
        @Override
        public boolean hasOther ()
        {
            return !_beforeOthers.isEmpty() || !_afterOthers.isEmpty();
        }
        
        public void addBeforeOthers (Resource r)
        {
            _beforeOthers.addLast(r);
        }
        
        public void addAfterOthers (Resource r)
        {
            _afterOthers.addLast(r);
        }
        
        public void addNoOthers (Resource r)
        {
            _noOthers.addLast(r);
        }
        
       protected boolean orderList (LinkedList<Resource> list)
       {
           //Take a copy of the list so we can iterate over it and at the same time do random insertions
           boolean changes = false;
           List<Resource> iterable = new ArrayList<Resource>(list);
           Iterator<Resource> itor = iterable.iterator();
           
           while (itor.hasNext())
           {
               Resource r = itor.next();
               FragmentDescriptor f = _metaData.getFragment(r);
               if (f == null)
               {
                   //no fragment for this resource so cannot have any ordering directives
                   continue;
               }
                
               //Handle any explicit <before> relationships for the fragment we're considering
               List<String> befores = f.getBefores();
               if (befores != null && !befores.isEmpty())
               {
                   for (String b: befores)
                   {
                       //Fragment we're considering must be before b
                       //Check that we are already before it, if not, move us so that we are.
                       //If the name does not exist in our list, then get it out of the no-other list
                       if (!isBefore(list, f.getName(), b))
                       {
                           //b is not already before name, move it so that it is
                           int idx1 = getIndexOf(list, f.getName());
                           int idx2 = getIndexOf(list, b);
    
                           //if b is not in the same list
                           if (idx2 < 0)
                           {
                               changes = true;
                               // must be in the noOthers list or it would have been an error
                               Resource bResource = _metaData.getJarForFragment(b);
                               if (bResource != null)
                               {
                                   //If its in the no-others list, insert into this list so that we are before it
                                   if (_noOthers.remove(bResource))
                                   {
                                       insert(list, idx1+1, b);
                                      
                                   }
                               }
                           }
                           else
                           {
                               //b is in the same list but b is before name, so swap it around
                               list.remove(idx1);
                               insert(list, idx2, f.getName());
                               changes = true;
                           }
                       }
                   }
               }
    
               //Handle any explicit <after> relationships
               List<String> afters = f.getAfters();
               if (afters != null && !afters.isEmpty())
               {
                   for (String a: afters)
                   {
                       //Check that fragment we're considering is after a, moving it if possible if its not
                       if (!isAfter(list, f.getName(), a))
                       {
                           //name is not after a, move it
                           int idx1 = getIndexOf(list, f.getName());
                           int idx2 = getIndexOf(list, a);
                           
                           //if a is not in the same list as name
                           if (idx2 < 0)
                           {
                               changes = true;
                               //take it out of the noOthers list and put it in the right place in this list
                               Resource aResource = _metaData.getJarForFragment(a);
                               if (aResource != null)
                               {
                                   if (_noOthers.remove(aResource))
                                   {
                                       insert(list,idx1, aResource);       
                                   }
                               }
                           }
                           else
                           {
                               //a is in the same list as name, but in the wrong place, so move it
                               list.remove(idx2);
                               insert(list,idx1, a);
                               changes = true;
                           }
                       }
                       //Name we're considering must be after this name
                       //Check we're already after it, if not, move us so that we are.
                       //If the name does not exist in our list, then get it out of the no-other list
                   }
               }
           }
    
           return changes;
       }
    
       /**
        * Is fragment with name a before fragment with name b?
        * @param list
        * @param fragNameA
        * @param fragNameB
        * @return true if fragment name A is before fragment name B 
        */
       protected boolean isBefore (List<Resource> list, String fragNameA, String fragNameB)
       {
           //check if a and b are already in the same list, and b is already
           //before a 
           int idxa = getIndexOf(list, fragNameA);
           int idxb = getIndexOf(list, fragNameB);
           
           
           if (idxb >=0 && idxb < idxa)
           {
               //a and b are in the same list but a is not before b
               return false;
           }
           
           if (idxb < 0)
           {
               //a and b are not in the same list, but it is still possible that a is before
               //b, depending on which list we're examining
               if (list == _beforeOthers)
               {
                   //The list we're looking at is the beforeOthers.If b is in the _afterOthers or the _noOthers, then by
                   //definition a is before it
                   return true;
               }
               else if (list == _afterOthers)
               {
                   //The list we're looking at is the afterOthers, then a will be the tail of
                   //the final list.  If b is in the beforeOthers list, then b will be before a and an error.
                   if (_beforeOthers.contains(fragNameB))
                       throw new IllegalStateException("Incorrect relationship: "+fragNameA+" before "+fragNameB);
                   else
                       return false; //b could be moved to the list
               }
           }
          
           //a and b are in the same list and a is already before b
           return true;
       }
    
    
       /**
        * Is fragment name "a" after fragment name "b"?
        * @param list
        * @param fragNameA
        * @param fragNameB
        * @return true if fragment name A is after fragment name B
        */
       protected boolean isAfter(List<Resource> list, String fragNameA, String fragNameB)
       {
           int idxa = getIndexOf(list, fragNameA);
           int idxb = getIndexOf(list, fragNameB);
           
           if (idxb >=0 && idxa < idxb)
           {
               //a and b are both in the same list, but a is before b
               return false;
           }
           
           if (idxb < 0)
           {
               //a and b are in different lists. a could still be after b depending on which list it is in.
    
               if (list == _afterOthers)
               {
                   //The list we're looking at is the afterOthers. If b is in the beforeOthers or noOthers then
                   //by definition a is after b because a is in the afterOthers list.
                   return true;
               }
               else if (list == _beforeOthers)
               {
                   //The list we're looking at is beforeOthers, and contains a and will be before
                   //everything else in the final ist. If b is in the afterOthers list, then a cannot be before b.
                   if (_afterOthers.contains(fragNameB))
                       throw new IllegalStateException("Incorrect relationship: "+fragNameB+" after "+fragNameA);
                   else
                       return false; //b could be moved from noOthers list
               }
           }
    
           return true; //a and b in the same list, a is after b
       }
    
       /**
        * Insert the resource matching the fragName into the list of resources
        * at the location indicated by index.
        * 
        * @param list
        * @param index
        * @param fragName
        */
       protected void insert(List<Resource> list, int index, String fragName)
       {
           Resource jar = _metaData.getJarForFragment(fragName);
           if (jar == null)
               throw new IllegalStateException("No jar for insertion");
           
           insert(list, index, jar);
       }
    
       protected void insert(List<Resource> list, int index, Resource resource)
       {
           if (list == null)
               throw new IllegalStateException("List is null for insertion");
           
           //add it at the end
           if (index > list.size())
               list.add(resource);
           else
               list.add(index, resource);
       }
    
       protected void remove (List<Resource> resources, Resource r)
       {
           if (resources == null)
               return;
           resources.remove(r);
       }
    
       protected int getIndexOf(List<Resource> resources, String fragmentName)
       {
          FragmentDescriptor fd = _metaData.getFragment(fragmentName);
          if (fd == null)
              return -1;
          
          
          Resource r = _metaData.getJarForFragment(fragmentName);
          if (r == null)
              return -1;
          
          return resources.indexOf(r);
       }
    }
  
}
