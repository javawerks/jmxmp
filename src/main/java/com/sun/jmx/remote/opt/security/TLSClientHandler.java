/*
 * @(#)file      TLSClientHandler.java
 * @(#)author    Sun Microsystems, Inc.
 * @(#)version   1.21
 * @(#)lastedit  07/03/08
 * @(#)build     @BUILD_TAG_PLACEHOLDER@
 *
 * 
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 * 
 * Copyright (c) 2007 Sun Microsystems, Inc. All Rights Reserved.
 * 
 * The contents of this file are subject to the terms of either the GNU General
 * Public License Version 2 only ("GPL") or the Common Development and
 * Distribution License("CDDL")(collectively, the "License"). You may not use
 * this file except in compliance with the License. You can obtain a copy of the
 * License at http://opendmk.dev.java.net/legal_notices/licenses.txt or in the 
 * LEGAL_NOTICES folder that accompanied this code. See the License for the 
 * specific language governing permissions and limitations under the License.
 * 
 * When distributing the software, include this License Header Notice in each
 * file and include the License file found at
 *     http://opendmk.dev.java.net/legal_notices/licenses.txt
 * or in the LEGAL_NOTICES folder that accompanied this code.
 * Sun designates this particular file as subject to the "Classpath" exception
 * as provided by Sun in the GPL Version 2 section of the License file that
 * accompanied this code.
 * 
 * If applicable, add the following below the License Header, with the fields
 * enclosed by brackets [] replaced by your own identifying information:
 * 
 *       "Portions Copyrighted [year] [name of copyright owner]"
 * 
 * Contributor(s):
 * 
 * If you wish your version of this file to be governed by only the CDDL or
 * only the GPL Version 2, indicate your decision by adding
 * 
 *       "[Contributor] elects to include this software in this distribution
 *        under the [CDDL or GPL Version 2] license."
 * 
 * If you don't indicate a single choice of license, a recipient has the option
 * to distribute your version of this file under either the CDDL or the GPL
 * Version 2, or to extend the choice of license to its licensees as provided
 * above. However, if you add GPL Version 2 code and therefore, elected the
 * GPL Version 2 license, then the option applies only if the new code is made
 * subject to such option by the copyright holder.
 * 
 */
package com.sun.jmx.remote.opt.security;

import com.sun.jmx.remote.generic.ProfileClient;
import com.sun.jmx.remote.opt.util.ClassLogger;
import com.sun.jmx.remote.socket.SocketConnectionIf;
import java.io.IOException;
import java.net.Socket;
import java.util.Map;
import java.util.StringTokenizer;
import javax.management.remote.generic.MessageConnection;
import javax.management.remote.message.ProfileMessage;
import javax.management.remote.message.TLSMessage;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * This class implements the client side TLS profile.
 */
public class TLSClientHandler implements ProfileClient {

    //-------------
    // Constructors
    //-------------
    public TLSClientHandler(String profile, Map env) {
        this.profile = profile;
        this.env = env;
    }

    //---------------------------------------
    // ProfileClient interface implementation
    //---------------------------------------
    @Override
    public void initialize(MessageConnection mc) throws IOException {

        this.mc = mc;

        // Check if instance of SocketConnectionIf
        // and retrieve underlying socket
        //
        Socket socket = null;
        if (mc instanceof SocketConnectionIf) {
            socket = ((SocketConnectionIf) mc).getSocket();
        } else {
            throw new IOException("Not an instance of SocketConnectionIf");
        }

        // Get SSLSocketFactory
        //
        SSLSocketFactory ssf = (SSLSocketFactory) env.get("jmx.remote.tls.socket.factory");

        if (ssf == null) {
            ssf = (SSLSocketFactory) SSLSocketFactory.getDefault();
        }

        String hostname = socket.getInetAddress().getHostName();
        int port = socket.getPort();
        LOGGER.trace("initialize", "TLS: Hostname = " + hostname);
        LOGGER.trace("initialize", "TLS: Port = " + port);
        ts = (SSLSocket) ssf.createSocket(socket, hostname, port, true);

        // Set the SSLSocket Client Mode
        //
        ts.setUseClientMode(true);
        LOGGER.trace("initialize", "TLS: Socket Client Mode = " + ts.getUseClientMode());

        // Set the SSLSocket Enabled Protocols
        //
        if (TLSServerHandler.bundledJSSE) {
            String enabledProtocols = (String) env.get("jmx.remote.tls.enabled.protocols");
            if (enabledProtocols != null) {
                StringTokenizer st = new StringTokenizer(enabledProtocols, " ");
                int tokens = st.countTokens();
                String enabledProtocolsList[] = new String[tokens];
                for (int i = 0; i < tokens; i++) {
                    enabledProtocolsList[i] = st.nextToken();
                }
                TLSServerHandler.setEnabledProtocols(ts, enabledProtocolsList);
            }
            if (LOGGER.traceOn()) {
                LOGGER.trace("initialize", "TLS: Enabled Protocols");
                String[] enabled_p = TLSServerHandler.getEnabledProtocols(ts);
                if (enabled_p != null) {
                    StringBuffer str_buffer = new StringBuffer();
                    for (int i = 0; i < enabled_p.length; i++) {
                        str_buffer.append(enabled_p[i]);
                        if (i + 1 < enabled_p.length) {
                            str_buffer.append(", ");
                        }
                    }
                    LOGGER.trace("initialize", "TLS: [" + str_buffer + "]");
                } else {
                    LOGGER.trace("initialize", "TLS: []");
                }
            }
        }

        // Set the SSLSocket Enabled Cipher Suites
        //
        String enabledCipherSuites = (String) env.get("jmx.remote.tls.enabled.cipher.suites");
        if (enabledCipherSuites != null) {
            StringTokenizer st = new StringTokenizer(enabledCipherSuites, " ");
            int tokens = st.countTokens();
            String enabledCipherSuitesList[] = new String[tokens];
            for (int i = 0; i < tokens; i++) {
                enabledCipherSuitesList[i] = st.nextToken();
            }
            ts.setEnabledCipherSuites(enabledCipherSuitesList);
        }
        if (LOGGER.traceOn()) {
            LOGGER.trace("initialize", "TLS: Enabled Cipher Suites");
            String[] enabled_cs = ts.getEnabledCipherSuites();
            if (enabled_cs != null) {
                StringBuffer str_buffer = new StringBuffer();
                for (int i = 0; i < enabled_cs.length; i++) {
                    str_buffer.append(enabled_cs[i]);
                    if (i + 1 < enabled_cs.length) {
                        str_buffer.append(", ");
                    }
                }
                LOGGER.trace("initialize", "TLS: [" + str_buffer + "]");
            } else {
                LOGGER.trace("initialize", "TLS: []");
            }
        }
    }

    @Override
    public ProfileMessage produceMessage() throws IOException {
        TLSMessage tlspm = new TLSMessage(TLSMessage.READY);
        LOGGER.trace("produceMessage", ">>>>> TLS client message <<<<<");
        LOGGER.trace("produceMessage", "Profile Name : " + tlspm.getProfileName());
        LOGGER.trace("produceMessage", "Status : " + tlspm.getStatus());
        return tlspm;
    }

    @Override
    public void consumeMessage(ProfileMessage pm) throws IOException {
        if (!(pm instanceof TLSMessage)) {
            throw new IOException("Unexpected profile message type: " + pm.getClass().getName());
        }
        TLSMessage tlspm = (TLSMessage) pm;
        LOGGER.trace("consumeMessage", ">>>>> TLS server message <<<<<");
        LOGGER.trace("consumeMessage", "Profile Name : " + tlspm.getProfileName());
        LOGGER.trace("consumeMessage", "Status : " + tlspm.getStatus());
        if (tlspm.getStatus() != TLSMessage.PROCEED) {
            throw new IOException("Unexpected TLS status ["
                    + tlspm.getStatus() + "]");
        }
        completed = true;
    }

    @Override
    public boolean isComplete() {
        return completed;
    }

    @Override
    public void activate() throws IOException {
        LOGGER.trace("activate", ">>>>> TLS handshake <<<<<");
        LOGGER.trace("activate", "TLS: Start TLS Handshake");
        ts.startHandshake();

        if (LOGGER.traceOn()) {
            SSLSession session = ts.getSession();
            if (session != null) {
                LOGGER.trace("activate", "TLS: getCipherSuite = "
                        + session.getCipherSuite());
                LOGGER.trace("activate", "TLS: getPeerHost = "
                        + session.getPeerHost());
                if (TLSServerHandler.bundledJSSE) {
                    LOGGER.trace("activate", "TLS: getProtocol = "
                            + TLSServerHandler.getProtocol(session));
                }
            }
            LOGGER.trace("activate", "TLS: Finish TLS Handshake");
        }

        // Set new TLS socket in MessageConnection
        //
        ((SocketConnectionIf) mc).setSocket(ts);
    }

    @Override
    public void terminate() throws IOException {
    }

    @Override
    public String getName() {
        return profile;
    }

    //--------------------
    // Protected variables
    //--------------------
    protected SSLSocket ts = null;

    //------------------
    // Private variables
    //------------------
    private boolean completed = false;
    private Map env = null;
    private MessageConnection mc = null;
    private String profile = null;
    private static final ClassLogger LOGGER = new ClassLogger("javax.management.remote.misc", "TLSClientHandler");
}
