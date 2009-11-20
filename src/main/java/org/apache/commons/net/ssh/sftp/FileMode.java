package org.apache.commons.net.ssh.sftp;

import java.util.EnumSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;

public class FileMode
{
    
    public static enum Type
    {
        /** block special */
        BLOCK_SPECIAL(0060000),
        /** character special */
        CHAR_SPECIAL(0020000),
        /** FIFO special */
        FIFO_SPECIAL(0010000),
        /** socket special */
        SOCKET_SPECIAL(0140000),
        /** regular */
        REGULAR(0100000),
        /** directory */
        DIRECTORY(0040000),
        /** symbolic link */
        SYMKLINK(0120000),
        /** unknown */
        UNKNOWN(0);
        
        private final int val;
        
        private Type(int val)
        {
            this.val = val;
        }
        
        public static Type fromMask(int mask)
        {
            for (Type t : Type.values())
                if (t.val == mask)
                    return t;
            return UNKNOWN;
        }
        
    }
    
    public static enum Permission
    {
        
        /** read permission, owner */
        USR_R(0000400),
        /** write permission, owner */
        USR_W(0000200),
        /** execute/search permission, owner */
        USR_X(0000100),
        /** read permission, group */
        GRP_R(0000040),
        /** write permission, group */
        GRP_W(0000020),
        /** execute/search permission, group */
        GRP_X(0000010),
        /** read permission, others */
        OTH_R(0000004),
        /** write permission, others */
        OTH_W(0000002),
        /** execute/search permission, group */
        OTH_X(0000001),
        /** set-user-ID on execution */
        SUID(0004000),
        /** set-group-ID on execution */
        SGID(0002000),
        /** on directories, restricted deletion flag */
        STICKY(0001000),
        // Composite:
        /** read, write, execute/search by user */
        USR_RWX(USR_R, USR_W, USR_X),
        /** read, write, execute/search by group */
        GRP_RWX(GRP_R, GRP_W, GRP_X),
        /** read, write, execute/search by other */
        OTH_RWX(OTH_R, OTH_W, OTH_X);
        
        private final int val;
        
        private Permission(int val)
        {
            this.val = val;
        }
        
        private Permission(Permission... perms)
        {
            int val = 0;
            for (Permission perm : perms)
                val |= perm.val;
            this.val = val;
        }
        
        public static Set<Permission> fromMask(int mask)
        {
            List<Permission> perms = new LinkedList<Permission>();
            for (Permission p : Permission.values())
                if ((mask & p.val) == p.val)
                    perms.add(p);
            return EnumSet.copyOf(perms);
        }
        
        public static int toMask(Set<Permission> perms)
        {
            int mask = 0;
            for (Permission p : perms)
                mask |= p.val;
            return mask;
        }
        
    }
    
    private final int mask;
    private final Type type;
    private final Set<Permission> perms;
    
    public FileMode(int mask)
    {
        this.mask = mask;
        this.type = Type.fromMask(getTypeMask());
        this.perms = Permission.fromMask(getPermissionsMask());
    }
    
    public int getMask()
    {
        return mask;
    }
    
    public int getTypeMask()
    {
        return mask & 0777000;
    }
    
    public int getPermissionsMask()
    {
        return mask & 0000777;
    }
    
    public Type getType()
    {
        return type;
    }
    
    public Set<Permission> getPermissions()
    {
        return perms;
    }
    
    public static FileMode fromPermissions(Set<Permission> perms)
    {
        return new FileMode(Permission.toMask(perms));
    }
    
}
