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
import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectOutputStream;
import java.io.OutputStream;
import java.nio.file.FileVisitOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.jetty.util.ClassLoadingObjectInputStream;
import org.eclipse.jetty.util.MultiException;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.annotation.ManagedAttribute;
import org.eclipse.jetty.util.annotation.ManagedObject;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

/**
 * FileSessionDataStore
 *
 * A file-based store of session data.
 */
@ManagedObject
public class FileSessionDataStore extends AbstractSessionDataStore
{
    
    
    private  final static Logger LOG = Log.getLogger("org.eclipse.jetty.server.session");
    private File _storeDir;
    private boolean _deleteUnrestorableFiles = false;
    private Map<String,String> _sessionFileMap = new ConcurrentHashMap<>();
    private String _contextString;

    @Override
    public void initialize(SessionContext context) throws Exception
    {
        super.initialize(context);
        _contextString = _context.getCanonicalContextPath()+"_"+_context.getVhost();
    }

    @Override
    protected void doStart() throws Exception
    {
        initializeStore();
        super.doStart();
    }

    @Override
    protected void doStop() throws Exception
    {
        _sessionFileMap.clear();
        super.doStop();
    }

    @ManagedAttribute(value="dir where sessions are stored", readonly=true)
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
     * Delete a session
     * 
     * @param id session id
     */
    @Override
    public boolean delete(String id) throws Exception
    {   
        if (_storeDir != null)
        {
            //remove from our map
            String filename = _sessionFileMap.remove(getIdWithContext(id));
            if (filename == null)
                return false;
            
            //remove the file
            return deleteFile(filename);
        }
         
        return false;
    }


    
    /**
     * Delete the file associated with a session
     * 
     * @param filename name of the file containing the session's information
     * 
     * @return true if file was deleted, false otherwise
     * @throws Exception
     */
    public boolean deleteFile (String filename) throws Exception
    {
        if (filename == null)
            return false;
        File file = new File(_storeDir, filename);
        return Files.deleteIfExists(file.toPath());
    }
    
    
    /** 
     * Check to see which sessions have expired.
     * 
     * @param candidates the set of session ids that the SessionCache believes
     * have expired
     * @return the complete set of sessions that have expired, including those
     * that are not currently loaded into the SessionCache
     */
    @Override
    public Set<String> doGetExpired(final Set<String> candidates)
    {
        final long now = System.currentTimeMillis();
        HashSet<String> expired = new HashSet<String>();

        //iterate over the files and work out which have expired
        for (String filename:_sessionFileMap.values())
        {
            try
            {
                long expiry = getExpiryFromFilename(filename);
                if (expiry > 0 && expiry < now)
                    expired.add(getIdFromFilename(filename));
            }
            catch (Exception e)
            {
                LOG.warn(e);
            }
        }
        
        //check candidates that were not found to be expired, perhaps 
        //because they no longer exist and they should be expired
        for (String c:candidates)
        {
            if (!expired.contains(c))
            {
                //if it doesn't have a file then the session doesn't exist
                String filename = _sessionFileMap.get(getIdWithContext(c));
                if (filename == null)
                    expired.add(c);
            }
        }
        
        //TODO iterate over ALL files in storeDir irrespective of context to
        //find ones that expired a long time ago??? Or maybe do that at startup???
        
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
                //load session info from its file
                String idWithContext = getIdWithContext(id);
                String filename = _sessionFileMap.get(idWithContext);
                if (filename == null)
                {
                    if (LOG.isDebugEnabled())
                        LOG.debug("Unknown file {}",filename);
                    return;
                }
                File file = new File (_storeDir, filename);
                if (!file.exists())
                {
                    if (LOG.isDebugEnabled())
                        LOG.debug("No such file {}",filename);
                    return;
                }  

                try (FileInputStream in = new FileInputStream(file))
                {
                    SessionData data = load(in, id);
                    data.setLastSaved(file.lastModified());
                    reference.set(data);
                }
                catch (UnreadableSessionDataException e)
                {
                    if (isDeleteUnrestorableFiles() && file.exists() && file.getParentFile().equals(_storeDir))
                    {
                        try
                        {
                            delete(id);
                            LOG.warn("Deleted unrestorable file for session {}", id);
                        }
                        catch (Exception x)
                        {
                            LOG.warn("Unable to delete unrestorable file {} for session {}", filename, id, x);
                        } 
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
            delete(id);
                  
            //make a fresh file using the latest session expiry
            String filename = getIdWithContextAndExpiry(data);
            String idWithContext = getIdWithContext(id);
            file = new File(_storeDir, filename);

            try(FileOutputStream fos = new FileOutputStream(file,false))
            {
                save(fos, id, data);
                _sessionFileMap.put(idWithContext, filename);
            }
            catch (Exception e)
            { 
                if (file != null) 
                    file.delete(); // No point keeping the file if we didn't save the whole session
                throw new UnwriteableSessionDataException(id, _context,e);             
            }
        }
    }
    
    /**
     * Read the names of the existing session files and build a map of
     * fully qualified session ids (ie with context) to filename.  If there
     * is more than one file for the same session, only the most recently modified will
     * be kept and the rest deleted.
     * 
     * @throws IllegalStateException if storeDir doesn't exist, isn't readable/writeable
     * or contains 2 files with the same lastmodify time for the same session. Throws IOException
     * if the lastmodifytimes can't be read.
     */
    public void initializeStore ()
    throws Exception
    {
        if (_storeDir == null)
            throw new IllegalStateException("No file store specified");

        if (!_storeDir.exists())
            _storeDir.mkdirs();
        else
        {
            if (!(_storeDir.isDirectory() &&_storeDir.canWrite() && _storeDir.canRead()))
                throw new IllegalStateException(_storeDir.getAbsolutePath()+" must be readable/writeable dir");

            //iterate over files in _storeDir and build map of session id to filename
            MultiException me = new MultiException();
            
                Files.walk(_storeDir.toPath(), 1, FileVisitOption.FOLLOW_LINKS)
                .filter(p->!Files.isDirectory(p)).filter(p->isSessionFilename(p.getFileName().toString()))
                .forEach(p->{
                    String filename = p.getFileName().toString();
                    String sessionIdWithContext = getIdWithContextFromFilename(filename);
                    if (sessionIdWithContext != null)
                    {
                        //handle multiple session files existing for the same session: remove all
                        //but the file with the most recent modify time
                        String existing = _sessionFileMap.putIfAbsent(sessionIdWithContext, filename);
                        if (existing != null)
                        {
                            //if there was a prior filename, work out which has the most
                            //recent modify time
                            try
                            {
                                Path existingPath = _storeDir.toPath().resolve(existing);
                                FileTime existingFileTime = Files.getLastModifiedTime(existingPath);
                                FileTime thisFileTime = Files.getLastModifiedTime(p);
                                int comparison = thisFileTime.compareTo(existingFileTime);
                                if (comparison == 0)
                                    me.add(new IllegalStateException(existingPath+" and "+p+" have same lastmodify time")); //fail startup
                                else if (comparison > 0)
                                {
                                    //update the file we're keeping
                                    _sessionFileMap.put(sessionIdWithContext, p.getFileName().toString());
                                    //delete the old file
                                    Files.delete(existingPath);
                                }
                                else
                                {
                                    Files.delete(p);
                                }
                            }
                            catch (IOException e)
                            {
                                me.add(e);
                            }
                        }
                    }
                });
                me.ifExceptionThrow();
        }
    }
    
    

    /** 
     * @see org.eclipse.jetty.server.session.SessionDataStore#isPassivating()
     */
    @Override
    @ManagedAttribute(value="are sessions serialized by this store", readonly=true)
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
        String idWithContext = getIdWithContext(id);
        String filename = _sessionFileMap.get(idWithContext);
  
       if (filename == null)
           return false;
       
       //check the expiry
       long expiry = getExpiryFromFilename(filename);
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
        return _contextString+"_"+id;
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
    
    
    private String getIdFromFilename (String filename)
    {
        if (filename == null)
            return null;
        return filename.substring(filename.lastIndexOf('_')+1);
    }
    
    
    
    private long getExpiryFromFilename (String filename)
    {
        if (StringUtil.isBlank(filename) || filename.indexOf("_") < 0)
            throw new IllegalStateException ("Invalid or missing filename");
        
        String s = filename.substring(0, filename.indexOf('_'));
        return (s==null?0:Long.parseLong(s));
    }
    
    
    private String getContextFromFilename (String filename)
    {
        if (StringUtil.isBlank(filename))
            return null;
        
        int start = filename.indexOf('_');
        int end = filename.lastIndexOf('_');
        return filename.substring(start+1, end);
    }

    
    /**
     * Extract the session id and context from the filename
     * @param filename the name of the file to use
     * @return the session id plus context
     */
    private String getIdWithContextFromFilename (String filename)
    {
        if (StringUtil.isBlank(filename) || filename.indexOf('_') < 0)
            return null;
        
        return filename.substring(filename.indexOf('_')+1);
    }
    
    /**
     * Check if the filename matches our session pattern
     * and is a session for our context.
     * 
     * @param filename the filename to check
     * @return true if the filename has the correct filename format and is for this context
     */
    private boolean isSessionFilename (String filename)
    {
        if (StringUtil.isBlank(filename))
            return false;
        String[] parts = filename.split("_");
        
        //Need at least 4 parts for a valid filename
        if (parts.length < 4)
            return false;
       
        //Also needs to be for our context
        String context = getContextFromFilename(filename);
        if (context == null)
            return false;
        return (_contextString.equals(context));
    }

    
    

    

    

    /**
     * @param is inputstream containing session data
     * @param expectedId the id we've been told to load
     * @return the session data
     * @throws Exception
     */
    private SessionData load (InputStream is, String expectedId)
            throws Exception
    {
        String id = null; //the actual id from inside the file

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
            throw new UnreadableSessionDataException(expectedId, _context, e);
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
