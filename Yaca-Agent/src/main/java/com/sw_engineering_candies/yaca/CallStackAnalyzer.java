/*
 * Copyright (C) 2012-2020, Markus Sprunck <sprunck.markus@gmail.com>
 *
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * - Redistributions of source code must retain the above copyright notice, this
 * list of conditions and the following disclaimer.
 *
 * - Redistributions in binary form must reproduce the above copyright notice,
 * this list of conditions and the following disclaimer in the documentation
 * and/or other materials provided with the distribution.
 *
 * - The name of its contributor may be used to endorse or promote products
 * derived from this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE
 * LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
 * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGE.
 */

package com.sw_engineering_candies.yaca;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.management.ManagementFactory;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Pattern;

import com.sun.tools.attach.AttachNotSupportedException;
import com.sun.tools.attach.VirtualMachine;
import com.sun.tools.attach.VirtualMachineDescriptor;
import lombok.extern.slf4j.Slf4j;
import sun.tools.attach.HotSpotVirtualMachine;

import org.springframework.stereotype.Component;

/**
 * The class collects call stack data from the VM
 */
@Slf4j
@Component
public class CallStackAnalyzer {

    protected static final CopyOnWriteArrayList<Integer> allVirtualMachines = new CopyOnWriteArrayList<>();

    /**
     * Constants
     */
    private static final String NL = System.getProperty("line.separator");

    private static final String INVALID_PROCESS_ID = "----";

    /**
     * Attributes
     */
    private static String currentProcessID = INVALID_PROCESS_ID;

    private static String newProcessID = "";

    protected final Model model;

    /**
     * Constructor
     */
    public CallStackAnalyzer(Model model) {
        this.model = model;
    }

    public synchronized static void findOtherAttachableJavaVMs() {

        allVirtualMachines.clear();

        List<VirtualMachineDescriptor> vmDesc = VirtualMachine.list();
        for (VirtualMachineDescriptor descriptor : vmDesc) {
            final String nextPID = descriptor.id();

            final String ownPID = ManagementFactory.getRuntimeMXBean().getName().split("@")[0];
            if (!ownPID.equals(nextPID)) {

                final StringBuilder message = new StringBuilder();
                message.append("Process ID=").append(nextPID).append(NL);

                VirtualMachine vm;
                try {
                    vm = VirtualMachine.attach(descriptor);

                    Properties props = vm.getSystemProperties();
                    message.append("   java.version=").append(props.getProperty("java.version")).append(NL);
                    message.append("   java.vendor=").append(props.getProperty("java.vendor")).append(NL);
                    message.append("   java.home=").append(props.getProperty("java.home")).append(NL);
                    message.append("   sun.arch.data.model=").append(props.getProperty("sun.arch.data.model")).append(NL);

                    Properties properties = vm.getAgentProperties();
                    Enumeration<Object> keys = properties.keys();
                    while (keys.hasMoreElements()) {
                        Object elementKey = keys.nextElement();
                        message.append("   ").append(elementKey).append("=").append(properties.getProperty(elementKey.toString())).append(NL);
                    }
                    log.debug(message.toString());
                    vm.detach();

                    int processId = Integer.parseInt(nextPID);
                    allVirtualMachines.add(processId);
                } catch (AttachNotSupportedException | IOException e) {
                    log.error(e.getMessage());
                }
            }
        }
        Collections.sort(allVirtualMachines);
        Collections.reverse(allVirtualMachines);
    }

    public synchronized static void setProcessNewID(String processIdNew) {
        String value = processIdNew.trim();
        try {
            Integer.valueOf(value);
            log.info("Set new process id=" + value);
            CallStackAnalyzer.newProcessID = value;
        } catch (Exception ex) {
            log.error("Invalid id=" + value);
        }
    }


    public void start() {

        log.info("CallStackAnalyzer started");

        HotSpotVirtualMachine hsVm = null;
        do {

            try {

                if (allVirtualMachines.size() == 0) {

                    findOtherAttachableJavaVMs();
                    log.debug("VirtualMachines=" + allVirtualMachines);

                    if (!allVirtualMachines.isEmpty()) {
                        newProcessID = allVirtualMachines.get(0).toString();
                        log.debug("Select pid=" + currentProcessID);
                        model.setConnected(true);
                    }
                }

                if (!currentProcessID.equals(newProcessID)) {
                    log.info("Request change to pid=" + newProcessID + " allVirtualMachines=" + allVirtualMachines);

                    // Attach to new virtual machine
                    hsVm = (HotSpotVirtualMachine) VirtualMachine.attach(newProcessID);
                    model.setActiveProcess(newProcessID);
                    model.reset();
                    currentProcessID = newProcessID;
                    model.setConnected(true);
                }

                if (model.isConnected()) {

                    // Update filter white list
                    final String filterWhite = model.getFilterWhiteList();
                    final String filterBlack = model.getFilterBlackList();
                    final Pattern patternWhiteList = Pattern.compile(filterWhite);
                    final Pattern patternBlackList = Pattern.compile(filterBlack);

                    try {
                        final List<Node> entryList = new ArrayList<>(10);
                        assert hsVm != null;
                        final InputStream in = hsVm.remoteDataDump();
                        final BufferedReader br = new BufferedReader(new InputStreamReader(in));
                        String line;
                        while ((line = br.readLine()) != null) {
                            if (line.startsWith("\tat ") && line.length() > 10) {
                                final String fullMethodName = line.substring(4, line.lastIndexOf('(')).trim();
                                if (filterWhite.isEmpty() || patternWhiteList.matcher(fullMethodName).find()) {
                                    if (filterBlack.isEmpty() || !patternBlackList.matcher(fullMethodName).find()) {
                                        final String[] split = fullMethodName.split("\\.");
                                        if (split.length > 2) {
                                            final int indexOfMethodName = split.length - 1;
                                            final int indexOfClassName = indexOfMethodName - 1;
                                            final StringBuilder packageName = new StringBuilder(line.length());
                                            packageName.append(split[0]);
                                            for (int i = 1; i < indexOfClassName; i++) {
                                                packageName.append('.').append(split[i]);
                                            }

                                            String className = split[indexOfClassName];
                                            String packageString = packageName.toString();
                                            final Node entry = new Node();
                                            String methodName = split[indexOfMethodName];
                                            entry.setMethodName(methodName);
                                            entry.setClassName(className);
                                            entry.setPackageName(packageString);
                                            entry.setNewItem(true);
                                            entryList.add(entry);

                                            log.debug("line='" + line + "'" + NL + "  packageString='" + packageString + NL + "  className='"
                                                    + className + NL + "  methodName='" + methodName + "'");
                                        } else {
                                            log.warn("Can't process line '" + line + "'");
                                        }
                                    }
                                }
                            }
                        }
                        model.append(entryList);

                        br.close();
                        in.close();

                    } catch (final IOException e) {
                        log.debug("IOException " + e.getMessage());
                        model.setConnected(false);
                    }
                }

                try {
                    Thread.sleep(10);
                } catch (InterruptedException e) {
                    log.error("Wait problem ", e.getMessage());
                }

            } catch (final AttachNotSupportedException e) {
                if (model.isConnected()) {
                    log.error("AttachNotSupportedException ", e);
                }
                model.reset();
            } catch (IOException e) {
                log.error("IOException " + e.getMessage());
                model.reset();
            }
        } while (true);
    }
}
