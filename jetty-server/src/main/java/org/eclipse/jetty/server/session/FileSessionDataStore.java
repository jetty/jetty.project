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


package org.eclipse.jetty.server.session;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FilenameFilter;
import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectOutputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.StringTokenizer;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.jetty.util.ClassLoadingObjectInputStream;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

/**
 * FileSessionDataStore
 *
 * A file-based store of session data.
 */
public class FileSessionDataStore extends AbstractSessionDataStore
{
    private  final static Logger LOG = Log.getLogger("org.eclipse.jetty.server.session");
    private File _storeDir;
    private boolean _deleteUnrestorableFiles = false;
    


    @Override
    protected void doStart() throws Exception
    {
        initializeStore();
        super.doStart();
    }

    @Override
    protected void doStop() throws Exception
    {
        super.doStop();
    }

    public File getStoreDir()
    {
        return _storeDir;
    }

    public void setStoreDir(File storeDir)
    {
        checkStarted();
        _storeDir = storeDir;
    }

    public boolean isDeleteUnrestorableFiles()
    {
        return _deleteUnrestorableFiles;
    }

    public void setDeleteUnrestorableFiles(boolean deleteUnrestorableFiles)
    {
        checkStarted();
        _deleteUnrestorableFiles = deleteUnrestorableFiles;
    }

 

    /** 
     * @see org.eclipse.jetty.server.session.SessionDataStore#delete(java.lang.String)
     */
    @Override
    public boolean delete(String id) throws Exception
    {   
        File file = null;
        if (_storeDir != null)
        {
            file = getFile(_storeDir, id);
            if (file != null && file.exists() && file.getParentFile().equals(_storeDir))
            {
                return file.delete();
            }
        }
         
        return false;
    }


    /** 
     * @see org.eclipse.jetty.server.session.SessionDataStore#getExpired(Set)
     */
    @Override
    public Set<String> doGetExpired(final Set<String> candidates)
    {
        final long now = System.currentTimeMillis();
        HashSet<String> expired = new HashSet<String>();
        
        HashSet<String> idsWithContext = new HashSet<>();
        
        
        //one pass to get all idWithContext
        File [] files = _storeDir.listFiles(new FilenameFilter()
        {  
            @Override
            public boolean accept(File dir, String name)
            {
                if (dir != _storeDir)
                    return false;

                //dir may contain files that don't match our naming pattern      
                if (!match(name))
                {
                    return false;
                }

                String idWithContext = getIdWithContextFromString(name);
                if (!StringUtil.isBlank(idWithContext))
                    idsWithContext.add(idWithContext);
                return true;
            }
        });
        
       
        //got the list of all sessionids with their contexts, remove all old files for each one
        for (String idWithContext:idsWithContext)
        {
            deleteOldFiles(_storeDir, idWithContext);
        }

        
        //now find sessions that have expired in any context
        files = _storeDir.listFiles(new FilenameFilter() 
        {      
            @Override
            public boolean accept(File dir, String name)
            {
                if (dir != _storeDir)
                    return false;

                //dir may contain files that don't match our naming pattern
                if (!match(name))
                    return false;
                
                try
                {
                    long expiry = getExpiryFromString(name);
                    return expiry > 0 && expiry < now;
                }
                catch (Exception e)
                {
                    return false;
                }
            }
        });
        
        if (files != null)
        {
            for (File f:files)
            {
                expired.add(getIdFromFile(f));
            }
        }
        
        //check candidates that were not found to be expired, perhaps they no
        //longer exist and they should be expired
        for (String c:candidates)
        {
            if (!expired.contains(c))
            {
                //check if the file exists
                File f = getFile(_storeDir, c);
                if (f == null || !f.exists())
                    expired.add(c);
            }
        }
        
        return expired;
    }



    /** 
     * @see org.eclipse.jetty.server.session.SessionDataStore#load(java.lang.String)
     */
    @Override
    public SessionData load(String id) throws Exception
    {  
        final AtomicReference<SessionData> reference = new AtomicReference<SessionData>();
        final AtomicReference<Exception> exception = new AtomicReference<Exception>();
        Runnable r = new Runnable()
        {
            public void run ()
            {
                //get rid of all but the newest file for a session
                File file = deleteOldFiles(_storeDir, getIdWithContext(id));
   
                if (file == null || !file.exists())
                {
                    if (LOG.isDebugEnabled())
                        LOG.debug("No file: {}",file);
                    return;
                }

                try (FileInputStream in = new FileInputStream(file))
                {
                    SessionData data = load(in);
                    data.setLastSaved(file.lastModified());
                    reference.set(data);
                }
                catch (UnreadableSessionDataException e)
                {
                    if (isDeleteUnrestorableFiles() && file.exists() && file.getParentFile().equals(_storeDir))
                    {
                        file.delete();
                        LOG.warn("Deleted unrestorable file for session {}", id);
                    }

                    exception.set(e);
                }
                catch (Exception e)
                {
                    exception.set(e);
                }
            }
        };
        //ensure this runs with the context classloader set
        _context.run(r);
        
        if (exception.get() != null)
            throw exception.get();
        
        return reference.get();
    }
    
        

    /** 
     * @see org.eclipse.jetty.server.session.AbstractSessionDataStore#doStore(java.lang.String, org.eclipse.jetty.server.session.SessionData, long)
     */
    @Override
    public void doStore(String id, SessionData data, long lastSaveTime) throws Exception
    {
        File file = null;
        if (_storeDir != null)
        {
            //remove any existing files for the session
            deleteAllFiles(_storeDir, getIdWithContext(id));
            
            //make a fresh file using the latest session expiry
            file = new File(_storeDir, getIdWithContextAndExpiry(data));

            try(FileOutputStream fos = new FileOutputStream(file,false))
            {
                save(fos, id, data);
            }
            catch (Exception e)
            { 
                e.printStackTrace();
                if (file != null) 
                    file.delete(); // No point keeping the file if we didn't save the whole session
                throw new UnwriteableSessionDataException(id, _context,e);             
            }
        }
    }
    
    /**
     * 
     */
    public void initializeStore ()
    {
        if (_storeDir == null)
            throw new IllegalStateException("No file store specified");

        if (!_storeDir.exists())
            _storeDir.mkdirs();
    }

    /** 
     * @see org.eclipse.jetty.server.session.SessionDataStore#isPassivating()
     */
    @Override
    public boolean isPassivating()
    {
        return true;
    }
    
    
    
    
    /** 
     * @see org.eclipse.jetty.server.session.SessionDataStore#exists(java.lang.String)
     */
    @Override
    public boolean exists(String id) throws Exception
    {
       File sessionFile = deleteOldFiles(_storeDir, getIdWithContext(id));
       if (sessionFile == null || !sessionFile.exists())
           return false;
       
       //check the expiry
       long expiry = getExpiryFromFile(sessionFile);
       if (expiry <= 0)
           return true; //never expires
       else
           return (expiry > System.currentTimeMillis()); //hasn't yet expired
    }

    /* ------------------------------------------------------------ */
    /**
     * @param os the output stream to save to
     * @param id identity of the session
     * @param data the info of the session
     * @throws IOException
     */
    private void save(OutputStream os, String id, SessionData data)  throws IOException
    {    
        DataOutputStream out = new DataOutputStream(os);
        out.writeUTF(id);
        out.writeUTF(_context.getCanonicalContextPath());
        out.writeUTF(_context.getVhost());
        out.writeUTF(data.getLastNode());
        out.writeLong(data.getCreated());
        out.writeLong(data.getAccessed());
        out.writeLong(data.getLastAccessed());
        out.writeLong(data.getCookieSet());
        out.writeLong(data.getExpiry());
        out.writeLong(data.getMaxInactiveMs());
        
        List<String> keys = new ArrayList<String>(data.getKeys());
        out.writeInt(keys.size());
        ObjectOutputStream oos = new ObjectOutputStream(out);
        for (String name:keys)
        {
            oos.writeUTF(name);
            oos.writeObject(data.getAttribute(name));
        }
    }

    /**
     * Get the session id with its context.
     * 
     * @param id identity of session
     * @return the session id plus context
     */
    private String getIdWithContext (String id)
    {
        return _context.getCanonicalContextPath()+"_"+_context.getVhost()+"_"+id;
    }
    
    /**
     * Get the session id with its context and its expiry time
     * @param data
     * @return the session id plus context and expiry
     */
    private String getIdWithContextAndExpiry (SessionData data)
    {
        return ""+data.getExpiry()+"_"+getIdWithContext(data.getId());
    }
    
    
    /**
     * Work out which session id the file relates to.
     * @param file the file to check
     * @return the session id the file relates to.
     */
    private String getIdFromFile (File file)
    {
        if (file == null)
            return null;
        String name = file.getName();
        
        return name.substring(name.lastIndexOf('_')+1);
    }
    
    /**
     * Get the expiry time of the session stored in the file.
     * @param file the file from which to extract the expiry time
     * @return the expiry time
     */
    private long getExpiryFromFile (File file)
    {
        if (file == null)
            return 0;
        
        return getExpiryFromString(file.getName());
    }
    
    
    private long getExpiryFromString (String filename)
    {
        if (StringUtil.isBlank(filename) || filename.indexOf("_") < 0)
            throw new IllegalStateException ("Invalid or missing filename");
        
        String s = filename.substring(0, filename.indexOf('_'));
        return (s==null?0:Long.parseLong(s));
    }
    
    /**
     * Extract the session id and context from the filename.
     * @param file the file whose name to use
     * @return the session id plus context
     */
    private String getIdWithContextFromFile (File file)
    {
        if (file == null)
            return null;
        
        String s =  getIdWithContextFromString(file.getName());
        return s;
    }
    
    /**
     * Extract the session id and context from the filename
     * @param filename the name of the file to use
     * @return the session id plus context
     */
    private String getIdWithContextFromString (String filename)
    {
        if (StringUtil.isBlank(filename) || filename.indexOf('_') < 0)
            return null;
        
        return filename.substring(filename.indexOf('_')+1);
    }
    
    /**
     * Check if the filename matches our session pattern
     * @param filename
     * @return
     */
    private boolean match (String filename)
    {
        if (StringUtil.isBlank(filename))
            return false;
        String[] parts = filename.split("_");
        
        //Need at least 4 parts for a valid filename
        if (parts.length < 4)
            return false;
        
        return true;
    }

    
    /**
     * Find a File for the session id for the current context.
     * 
     * @param storeDir the session storage directory
     * @param id the session id
     * @return the file
     */
    private File getFile (final File storeDir, final String id)
    {
        File[] files = storeDir.listFiles (new FilenameFilter() {

            /** 
             * @see java.io.FilenameFilter#accept(java.io.File, java.lang.String)
             */
            @Override
            public boolean accept(File dir, String name)
            {
                if (dir != storeDir)
                    return false;
                return (name.contains(getIdWithContext(id)));
            }
            
        });
        
        if (files == null || files.length < 1)
            return null;
        return files[0];
    }
    
    
    private void deleteAllFiles(final File storeDir, final String idInContext)
    {
        File[] files = storeDir.listFiles (new FilenameFilter() {

            /** 
             * @see java.io.FilenameFilter#accept(java.io.File, java.lang.String)
             */
            @Override
            public boolean accept(File dir, String name)
            {
                if (dir != storeDir)
                    return false;
                return (name.contains(idInContext));
            }
            
        });
        
        //no files for that id
        if (files == null || files.length < 1)
            return;
        
        //delete all files
        for (File f:files)
        {
           f.delete();
        }
    }
    
    
    
    /**
     * Delete all but the most recent file for a given session id in a context.
     * 
     * @param storeDir the directory in which sessions are stored
     * @param idWithContext the id of the session
     * @return the most recent remaining file for the session, can be null
     */
    private File deleteOldFiles (final File storeDir, final String idWithContext)
    {
        File[] files = storeDir.listFiles (new FilenameFilter() {

            /** 
             * @see java.io.FilenameFilter#accept(java.io.File, java.lang.String)
             */
            @Override
            public boolean accept(File dir, String name)
            {
                if (dir != storeDir)
                    return false;
                
                if (!match(name))
                    return false;
                
                return (name.contains(idWithContext));
            }
            
        });
        
        //no file for that session
        if (files == null || files.length == 0)
            return null;

        
        //delete all but the most recent file
        File file = null;
        for (File f:files)
        {
            if (file == null)
                file = f;
            else
            {
               //accept the newest file
                if (f.lastModified() > file.lastModified())
                {
                    file.delete();
                    file = f;
                }
                else
                {
                    f.delete();
                }
            }
        }

        return file;
    }
    
    
    

    /**
     * @param is inputstream containing session data
     * @return the session data
     * @throws Exception
     */
    private SessionData load (InputStream is)
            throws Exception
    {
        String id = null;

        try
        {
            SessionData data = null;
            DataInputStream di = new DataInputStream(is);

            id = di.readUTF();
            String contextPath = di.readUTF();
            String vhost = di.readUTF();
            String lastNode = di.readUTF();
            long created = di.readLong();
            long accessed = di.readLong();
            long lastAccessed = di.readLong();
            long cookieSet = di.readLong();
            long expiry = di.readLong();
            long maxIdle = di.readLong();

            data = newSessionData(id, created, accessed, lastAccessed, maxIdle); 
            data.setContextPath(contextPath);
            data.setVhost(vhost);
            data.setLastNode(lastNode);
            data.setCookieSet(cookieSet);
            data.setExpiry(expiry);
            data.setMaxInactiveMs(maxIdle);

            // Attributes
            restoreAttributes(di, di.readInt(), data);

            return data;        
        }
        catch (Exception e)
        {
            throw new UnreadableSessionDataException(id, _context, e);
        }
    }

    /**
     * @param is inputstream containing session data
     * @param size number of attributes
     * @param data the data to restore to
     * @throws Exception
     */
    private void restoreAttributes (InputStream is, int size, SessionData data)
            throws Exception
    {
        if (size>0)
        {
            // input stream should not be closed here
            Map<String,Object> attributes = new HashMap<String,Object>();
            ClassLoadingObjectInputStream ois =  new ClassLoadingObjectInputStream(is);
            for (int i=0; i<size;i++)
            {
                String key = ois.readUTF();
                Object value = ois.readObject();
                attributes.put(key,value);
            }
            data.putAllAttributes(attributes);
        }
    }

    /** 
     * @see org.eclipse.jetty.server.session.AbstractSessionDataStore#toString()
     */
    @Override
    public String toString()
    {
        return String.format("%s[dir=%s,deleteUnrestorableFiles=%b]",super.toString(),_storeDir,_deleteUnrestorableFiles);
    }



}
