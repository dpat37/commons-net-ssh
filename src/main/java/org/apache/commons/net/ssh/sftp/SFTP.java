/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.commons.net.ssh.sftp;

import java.io.IOException;
import java.io.OutputStream;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.Map.Entry;

import org.apache.commons.net.ssh.SSHClient;
import org.apache.commons.net.ssh.connection.ConnectionException;
import org.apache.commons.net.ssh.connection.Session.Subsystem;
import org.apache.commons.net.ssh.sftp.Response.StatusCode;
import org.apache.commons.net.ssh.transport.TransportException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SFTP
{
    
    /** Logger */
    protected final Logger log = LoggerFactory.getLogger(getClass());
    
    public static final int PROTOCOL_VERSION = 3;
    
    public int timeout = 60;
    
    private final Subsystem sub;
    private final PacketReader reader;
    private final OutputStream out;
    
    private long reqID;
    
    private int negotiatedVersion;
    
    private final Map<String, String> serverExtensions = new HashMap<String, String>();
    
    public SFTP(SSHClient ssh) throws ConnectionException, TransportException
    {
        sub = ssh.startSession().startSubsystem("sftp");
        out = sub.getOutputStream();
        reader = new PacketReader(sub.getInputStream());
    }
    
    public Subsystem getSubsystem()
    {
        return sub;
    }
    
    public void init() throws IOException
    {
        Packet pk = new Packet();
        pk.putByte(PacketType.INIT.toByte());
        pk.putInt(PROTOCOL_VERSION);
        transmit(pk);
        
        Packet response = reader.readPacket();
        PacketType type = response.readType();
        if (type != PacketType.VERSION)
            throw new SFTPException("Expected INIT packet, received: " + type);
        negotiatedVersion = response.readInt();
        log.info("Client version {}, server version {}", PROTOCOL_VERSION, negotiatedVersion);
        if (negotiatedVersion < PROTOCOL_VERSION)
            throw new SFTPException("Server reported protocol version: " + negotiatedVersion);
        
        while (response.available() > 0)
            serverExtensions.put(response.readString(), response.readString());
        
        for (Entry<String, String> ext : serverExtensions.entrySet())
            System.out.println(ext.getKey() + ": " + ext.getValue());
        
        // Start reader thread
        reader.start();
    }
    
    public void send(Request req) throws IOException
    {
        reader.expectResponseTo(req);
        transmit(req);
    }
    
    public RemoteFile open(String filename, Set<OpenMode> modes, FileAttributes fa) throws IOException
    {
        Request req = newRequest(PacketType.OPEN);
        req.putString(filename);
        req.putInt(OpenMode.toMask(modes));
        req.putFileAttributes(fa);
        
        send(req);
        
        Response res = req.getFuture().get(timeout);
        res.ensurePacket(PacketType.DATA);
        return new RemoteFile(this, res.readString());
    }
    
    public RemoteFile open(String filename, Set<OpenMode> modes) throws IOException
    {
        return open(filename, modes, new FileAttributes.Builder().build());
    }
    
    public RemoteFile open(String filename) throws IOException
    {
        return open(filename, EnumSet.of(OpenMode.READ));
    }
    
    public RemoteDir openDir(String path) throws IOException
    {
        Request req = newRequest(PacketType.OPENDIR);
        req.putString(path);
        
        send(req);
        
        Response res = req.getFuture().get(timeout);
        res.ensurePacket(PacketType.HANDLE);
        return new RemoteDir(this, res.readString());
    }
    
    public void setAttributes(String path, FileAttributes attrs) throws IOException
    {
        Request req = newRequest(PacketType.SETSTAT);
        req.putString(path);
        send(req);
        Response res = req.getFuture().get(timeout);
        res.ensureStatusOK();
    }
    
    public String readLink(String path) throws IOException
    {
        Request req = newRequest(PacketType.READLINK);
        req.putString(path);
        send(req);
        return oneName(req.getFuture().get(timeout));
    }
    
    public void makeDirectory(String path, FileAttributes attrs) throws IOException
    {
        Request req = newRequest(PacketType.MKDIR);
        req.putString(path);
        req.putFileAttributes(attrs);
        req.getFuture().get(timeout).ensureStatusOK();
    }
    
    public void makeDirectory(String path) throws IOException
    {
        makeDirectory(path, new FileAttributes());
    }
    
    public void makeSymlink(String linkPath, String targetPath) throws IOException
    {
        Request req = newRequest(PacketType.SYMLINK);
        req.putString(linkPath).putString(targetPath);
        send(req);
        req.getFuture().get(timeout).ensureStatusOK();
    }
    
    public void remove(String filename) throws IOException
    {
        Request req = newRequest(PacketType.REMOVE);
        req.putString(filename);
        send(req);
        req.getFuture().get(timeout).ensureStatusOK();
    }
    
    public void removeDir(String path) throws IOException
    {
        Request req = newRequest(PacketType.RMDIR);
        req.putString(path);
        send(req);
        req.getFuture().get(timeout).ensureStatus(StatusCode.OK);
    }
    
    public FileAttributes stat(String path) throws IOException
    {
        return stat(path, true);
    }
    
    public FileAttributes stat(String path, boolean followSymlinks) throws IOException
    {
        Request req = newRequest(followSymlinks ? PacketType.STAT : PacketType.LSTAT);
        req.putString(path);
        send(req);
        Response res = req.getFuture().get(timeout);
        res.ensurePacket(PacketType.ATTRS);
        return res.readFileAttributes();
    }
    
    public void rename(String oldPath, String newPath) throws IOException
    {
        Request req = newRequest(PacketType.RENAME);
        req.putString(oldPath).putString(newPath);
        send(req);
        req.getFuture().get(timeout).ensureStatusOK();
    }
    
    public String getCanonicalizedPath(String path) throws IOException
    {
        Request req = newRequest(PacketType.REALPATH);
        req.putString(path);
        send(req);
        return oneName(req.getFuture().get(timeout));
    }
    
    public String oneName(Response res) throws IOException
    {
        res.ensurePacket(PacketType.NAME);
        if (res.readInt() == 1)
            return res.readString();
        else
            throw new SFTPException("Unexpected data in " + res.getType() + " packet");
    }
    
    public int getOperativeProtocolVersion()
    {
        return negotiatedVersion;
    }
    
    public synchronized Request newRequest(PacketType type)
    {
        return new Request(type, reqID + 1 & 0xffffffffL);
    }
    
    public void transmit(Packet payload) throws IOException
    {
        final int len = payload.available();
        out.write((byte) (len << 24 & 0xff000000));
        out.write((byte) (len << 16 & 0x00ff0000));
        out.write((byte) (len << 8 & 0x0000ff00));
        out.write((byte) (len & 0x000000ff));
        out.write(payload.array(), 0, len);
        out.flush();
    }
    
}
