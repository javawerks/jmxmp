/*
 * @(#)file      SynchroMessageConnectionServer.java
 * @(#)author    Sun Microsystems, Inc.
 * @(#)version   1.5
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
package com.sun.jmx.remote.generic;

import java.io.IOException;
import java.util.Map;
import javax.management.remote.JMXServiceURL;

/**
 * Interface specifying how a connector server creates new connections to clients.
 */
public interface SynchroMessageConnectionServer {

    /**
     * Activates this server for new client connections. Before this call is made, new client connections are not
     * accepted. The behavior is unspecified if this method is called more than once.
     *
     * @param env the properties of the connector server.
     *
     * @exception IOException if the server cannot be activated.
     */
    void start(Map env) throws IOException;

    /**
     * Listens for a connection to be made to this server and accepts it. The method blocks until a connection is made.
     *
     * @return a new <code>SynchroMessageConnection</code> object.
     * @exception IOException if an I/O error occurs when waiting for a connection.
     */
    ServerSynchroMessageConnection accept() throws IOException;

    /**
     * Terminates this server. On return from this method, new connection attempts are refused. Existing connections are
     * unaffected by this call. The behavior is unspecified if this method is called before the {@link #start} method.
     *
     * @exception IOException if an I/O error occurs when stopping the server. A best effort will have been made to
     * clean up the server's resources. The caller will not call {@link #accept} after <code>stop()</code>, whether or
     * not it gets <code>IOException</code>.
     */
    void stop() throws IOException;

    /**
     * The address of this connection server.
     *
     * @return the address of this connection server.
     */
    JMXServiceURL getAddress();
}
