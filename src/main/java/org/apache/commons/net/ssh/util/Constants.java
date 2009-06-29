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
package org.apache.commons.net.ssh.util;

/**
 * This interface defines constants for the SSH protocol.
 * 
 * @author <a href="mailto:dev@mina.apache.org">Apache MINA SSHD Project</a>
 * @author <a href="mailto:shikhar@schmizz.net">Shikhar Bhushan</a>
 */
public interface Constants
{
    /**
     * SSH message identifiers
     */
    public enum Message
    {
        
        SSH_MSG_DISCONNECT(1),
        SSH_MSG_IGNORE(2),
        SSH_MSG_UNIMPLEMENTED(3),
        SSH_MSG_DEBUG(4),
        SSH_MSG_SERVICE_REQUEST(5),
        SSH_MSG_SERVICE_ACCEPT(6),
        SSH_MSG_KEXINIT(20),
        SSH_MSG_NEWKEYS(21),
        
        SSH_MSG_KEXDH_INIT(30),
        
        /**
         * { SSH_MSG_KEXDH_REPLY, SSH_MSG_KEXDH_GEX_GROUP }
         */
        SSH_MSG_KEXDH_31(31),
        
        SSH_MSG_KEX_DH_GEX_INIT(32),
        SSH_MSG_KEX_DH_GEX_REPLY(33),
        SSH_MSG_KEX_DH_GEX_REQUEST(34),
        
        SSH_MSG_USERAUTH_REQUEST(50),
        SSH_MSG_USERAUTH_FAILURE(51),
        SSH_MSG_USERAUTH_SUCCESS(52),
        SSH_MSG_USERAUTH_BANNER(53),
        
        /**
         * { SSH_MSG_USERAUTH_PASSWD_CHANGREQ, SSH_MSG_USERAUTH_PK_OK, SSH_MSG_USERAUTH_INFO_REQUEST
         * }
         */
        SSH_MSG_USERAUTH_60(60),
        
        SSH_MSG_USERAUTH_INFO_RESPONSE(61),
        
        SSH_MSG_GLOBAL_REQUEST(80),
        SSH_MSG_REQUEST_SUCCESS(81),
        SSH_MSG_REQUEST_FAILURE(82),
        
        SSH_MSG_CHANNEL_OPEN(90),
        SSH_MSG_CHANNEL_OPEN_CONFIRMATION(91),
        SSH_MSG_CHANNEL_OPEN_FAILURE(92),
        SSH_MSG_CHANNEL_WINDOW_ADJUST(93),
        SSH_MSG_CHANNEL_DATA(94),
        SSH_MSG_CHANNEL_EXTENDED_DATA(95),
        SSH_MSG_CHANNEL_EOF(96),
        SSH_MSG_CHANNEL_CLOSE(97),
        SSH_MSG_CHANNEL_REQUEST(98),
        SSH_MSG_CHANNEL_SUCCESS(99),
        SSH_MSG_CHANNEL_FAILURE(100);
        
        private final byte b;
        
        static Message[] commands;
        
        static {
            commands = new Message[256];
            for (Message c : Message.values())
                if (commands[c.toByte()] == null)
                    commands[c.toByte()] = c;
        }
        
        public static Message fromByte(byte b)
        {
            return commands[b];
        }
        
        private Message(int b)
        {
            this.b = (byte) b;
        }
        
        public byte toByte()
        {
            return b;
        }
    }
    
    /**
     * Software version; sent as part of client identification string
     */
    String VERSION = "NET_3_0";
    
    /**
     * Default SSH port
     */
    int DEFAULT_PORT = 22;
    
    /**
     * SSH identifier for RSA keys
     */
    String SSH_RSA = "ssh-rsa";
    
    /**
     * SSH identifier for DSA keys
     */
    String SSH_DSS = "ssh-dss";
    
    //
    // Values for the algorithms negotiation
    //
    static final int PROPOSAL_KEX_ALGS = 0;
    int PROPOSAL_SERVER_HOST_KEY_ALGS = 1;
    int PROPOSAL_ENC_ALGS_CTOS = 2;
    int PROPOSAL_ENC_ALGS_STOC = 3;
    int PROPOSAL_MAC_ALGS_CTOS = 4;
    int PROPOSAL_MAC_ALGS_STOC = 5;
    int PROPOSAL_COMP_ALGS_CTOS = 6;
    int PROPOSAL_COMP_ALGS_STOC = 7;
    int PROPOSAL_LANG_CTOS = 8;
    int PROPOSAL_LANG_STOC = 9;
    int PROPOSAL_MAX = 10;
    
    //
    // Disconnect error codes
    //
    int SSH_DISCONNECT_HOST_NOT_ALLOWED_TO_CONNECT = 1;
    int SSH_DISCONNECT_PROTOCOL_ERROR = 2;
    int SSH_DISCONNECT_KEY_EXCHANGE_FAILED = 3;
    int SSH_DISCONNECT_HOST_AUTHENTICATION_FAILED = 4;
    int SSH_DISCONNECT_RESERVED = 4;
    int SSH_DISCONNECT_MAC_ERROR = 5;
    int SSH_DISCONNECT_COMPRESSION_ERROR = 6;
    int SSH_DISCONNECT_SERVICE_NOT_AVAILABLE = 7;
    int SSH_DISCONNECT_PROTOCOL_VERSION_NOT_SUPPORTED = 8;
    int SSH_DISCONNECT_HOST_KEY_NOT_VERIFIABLE = 9;
    int SSH_DISCONNECT_CONNECTION_LOST = 10;
    int SSH_DISCONNECT_BY_APPLICATION = 11;
    int SSH_DISCONNECT_TOO_MANY_CONNECTIONS = 12;
    int SSH_DISCONNECT_AUTH_CANCELLED_BY_USER = 13;
    int SSH_DISCONNECT_NO_MORE_AUTH_METHODS_AVAILABLE = 14;
    int SSH_DISCONNECT_ILLEGAL_USER_NAME = 15;
    
    //
    // Open error codes
    //
    
    int SSH_OPEN_ADMINISTRATIVELY_PROHIBITED = 1;
    int SSH_OPEN_CONNECT_FAILED = 2;
    int SSH_OPEN_UNKNOWN_CHANNEL_TYPE = 3;
    int SSH_OPEN_RESOURCE_SHORTAGE = 4;
    
}