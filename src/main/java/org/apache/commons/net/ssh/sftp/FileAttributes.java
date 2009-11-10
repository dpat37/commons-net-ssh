package org.apache.commons.net.ssh.sftp;

import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;

import org.apache.commons.net.ssh.util.Buffer;

public class FileAttributes
{
    
    private static enum Flag
    {
        
        SIZE(0x00000001), UIDGID(0x00000002), PERMISSIONS(0x00000004), ACMODTIME(0x00000008), EXTENDED(0x80000000);
        
        private final int flag;
        
        private Flag(int flag)
        {
            this.flag = flag;
        }
        
        public boolean isSet(int mask)
        {
            return (mask & flag) == flag;
        }
        
        public int setAndGet(int mask)
        {
            return (mask | flag);
        }
        
    }
    
    private final int mask;
    private final long size;
    private final long uid;
    private final long gid;
    private final long perms;
    private final long atime;
    private final long mtime;
    private final Map<String, String> ext = new HashMap<String, String>();
    
    private boolean isSet(Flag flag)
    {
        return flag.isSet(mask);
    }
    
    public FileAttributes(Packet buf)
    {
        mask = buf.readInt();
        
        size = isSet(Flag.SIZE) ? buf.getUINT64() : 0;
        
        if (isSet(Flag.UIDGID))
        {
            uid = buf.readLong();
            gid = buf.readLong();
        } else
        {
            uid = 0;
            gid = 0;
        }
        
        perms = isSet(Flag.PERMISSIONS) ? buf.readInt() : 0;
        
        if (isSet(Flag.ACMODTIME))
        {
            atime = buf.readLong();
            mtime = buf.readLong();
        } else
        {
            atime = 0;
            mtime = 0;
        }
        
        if (isSet(Flag.EXTENDED))
        {
            final int extCount = buf.readInt();
            for (int i = 0; i < extCount; i++)
                ext.put(buf.readString(), buf.readString());
        }
    }
    
    private FileAttributes(int mask, long size, long uid, long gid, long perms, long atime, long mtime,
            Map<String, String> ext)
    {
        this.mask = mask;
        this.size = size;
        this.uid = uid;
        this.gid = gid;
        this.perms = perms;
        this.atime = atime;
        this.mtime = mtime;
        this.ext.putAll(ext);
    }
    
    public long getSize()
    {
        return size;
    }
    
    public long getUID()
    {
        return uid;
    }
    
    public long getGID()
    {
        return gid;
    }
    
    public long getPermissions()
    {
        return perms;
    }
    
    public long getAtime()
    {
        return atime;
    }
    
    public long getMtime()
    {
        return mtime;
    }
    
    public String getExtended(String type)
    {
        return ext.get(type);
    }
    
    public Buffer toBuffer()
    {
        Buffer buf = new Buffer();
        buf.putInt(mask);
        if (isSet(Flag.SIZE))
            buf.putUINT64(size);
        if (isSet(Flag.UIDGID))
        {
            buf.putInt(uid);
            buf.putInt(gid);
        }
        if (isSet(Flag.PERMISSIONS))
            buf.putInt(perms);
        if (isSet(Flag.ACMODTIME))
        {
            buf.putInt(atime);
            buf.putInt(mtime);
        }
        if (isSet(Flag.EXTENDED))
        {
            buf.putInt(ext.size());
            for (Entry<String, String> entry : ext.entrySet())
            {
                buf.putString(entry.getKey());
                buf.putString(entry.getValue());
            }
        }
        return buf;
    }
    
    public static class Builder
    {
        
        private int mask;
        private long size;
        private long atime;
        private long mtime;
        private long perms;
        private long uid;
        private long gid;
        private final Map<String, String> ext = new HashMap<String, String>();
        
        public Builder withSize(long size)
        {
            mask = Flag.SIZE.setAndGet(mask);
            this.size = size;
            return this;
        }
        
        public Builder withAtime(long atime, long mtime)
        {
            mask = Flag.ACMODTIME.setAndGet(mask);
            this.atime = atime;
            this.mtime = mtime;
            return this;
        }
        
        public Builder withUIDGID(long uid, long gid)
        {
            mask = Flag.UIDGID.setAndGet(mask);
            this.uid = uid;
            this.gid = gid;
            return this;
        }
        
        public Builder withPermissions(long perms)
        {
            mask = Flag.PERMISSIONS.setAndGet(mask);
            this.perms = perms;
            return this;
        }
        
        public Builder withExtended(String type, String data)
        {
            mask = Flag.EXTENDED.setAndGet(mask);
            ext.put(type, data);
            return this;
        }
        
        public Builder withExtended(Map<String, String> ext)
        {
            mask = Flag.EXTENDED.setAndGet(mask);
            this.ext.putAll(ext);
            return this;
        }
        
        public FileAttributes build()
        {
            return new FileAttributes(mask, size, uid, gid, perms, atime, mtime, ext);
        }
        
    }
    
}
