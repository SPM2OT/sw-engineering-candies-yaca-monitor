/*
 * Copyright (C) 2012-2014, Markus Sprunck <sprunck.markus@gmail.com>
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
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Arrays;
import java.util.List;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

/**
 * The class is a minimal web server to provide data to a external client
 */
public class WebServer extends Thread {
	
	/**
	 * Constants
	 */
	private static final Log	LOGGER			= LogFactory.getLog(WebServer.class);
	
	private static final String	NL				= System.getProperty("line.separator");
	
	private final List<String>	STATIC_JS_FILES	= Arrays.asList("dat.gui.min.js", //
														"detector.js", //
														"three.js", //
														"trackballcontrols.js", //
														"projector.js", //
														"helvetiker_regular.typeface.js", //
														"stats.min.js", //
														"traceur.js", //
														"bootstrap.js", //
														"geometryutils.js", //
														"detector.js");
	
	private ClassLoader			classLoader		= null;
	
	private Model				model			= null;
	
	private final int			port;
	
	public WebServer(final int port, Model model) {
		this.port = port;
		this.model = model;
		
		if (classLoader == null) {
			classLoader = Class.class.getClassLoader();
		}
	}
	
	@Override
	public void run() {
		ServerSocket server = null;
		try {
			server = new ServerSocket(this.port);
		} catch (IOException ex) {
			LOGGER.error(ex);
			stopServer();
		}
		
		while (server != null) {
			
			try {
				// get connection
				final Socket socket = server.accept();
				socket.setSoTimeout(1000);
				final OutputStream out = socket.getOutputStream();
				final InputStream in = socket.getInputStream();
				
				// handle request
				RequestData request = new RequestData(in);
				if (request.startsWith("GET /favicon.ico")) {
					sendResponsForStaticFile(out, request.getFirstLine(), "favicon.ico", "image/x-icon");
				} else if (request.startsWith("GET /monitor/styles/main.css")) {
					sendResponsForStaticFile(out, request.getFirstLine(), "styles/main.css", "text/css");
				} else if (request.startsWith("GET /monitor/external")) {
					sendResponsForAllStaticJavaScriptFiles(out, request.getFirstLine());
				} else if (request.startsWith("DELETE /tasks")) {
					model.reset();
					sendResponse(out, "OK");
				} else if (request.startsWith("DELETE /filterWhite")) {
					model.setFilterWhiteList("");
					model.reset();
					sendResponse(out, "OK");
				} else if (request.startsWith("DELETE /filterBlack")) {
					model.setFilterBlackList("");
					model.reset();
					sendResponse(out, "OK");
				} else if (request.startsWith("PUT /filterWhite")) {
					model.setFilterWhiteList(request.getBody());
					model.reset();
					sendResponse(out, "OK");
				} else if (request.startsWith("PUT /filterBlack")) {
					model.setFilterBlackList(request.getBody());
					model.reset();
					sendResponse(out, "OK");
				} else if (request.startsWith("GET /process/ids")) {
					sendResponseForVMRequest(out);
				} else if (request.startsWith("PUT /process/id")) {
					Analyser.setProcessNewID(request.getBody());
					sendResponse(out, "OK");
				} else if (request.startsWith("GET /process")) {
					sendResponseForModelRequest(out);
				} else if (request.startsWith("GET /monitor")) {
					sendResponsForStaticFile(out, request.getFirstLine(), "index.html", "text/html");
				} else {
					LOGGER.warn("Not expected request=" + request.getFirstLine());
				}
				
				try {
					in.close();
					out.close();
					socket.close();
				} catch (final IOException e) {
					LOGGER.warn("Could not close resources : " + e);
				}
				
			} catch (final Exception e) {
				LOGGER.warn("Could not handle request: " + e);
			}
		}
	}
	
	private void stopServer() {
		LOGGER.info("server stopped");
		System.exit(0);
	}
	
	private void sendResponsForAllStaticJavaScriptFiles(final OutputStream out, String request) throws Exception {
		for (String name : STATIC_JS_FILES) {
			if (request.contains(name)) {
				sendResponsForStaticFile(out, request, "external/" + name, "application/javascript");
			}
		}
	}
	
	private void sendResponseForModelRequest(final OutputStream out) throws UnsupportedEncodingException, IOException {
		
		// Create content of response
		final String jsonpModel = model.getJSONPModel().toString();
		
		// For HTTP/1.0 or later send a MIME header
		final String headerString = "HTTP/1.1 200 OK" + NL + "Server: YacaAgent 2.0" + NL + "Content-Type: application/json" + NL
				+ "Content-Length: " + jsonpModel.toString().getBytes("UTF-8").length + NL + NL;
		
		out.write(headerString.getBytes("UTF-8"));
		out.write(jsonpModel.toString().getBytes("UTF-8"));
		out.flush();
	}
	
	private void sendResponseForVMRequest(final OutputStream out) throws UnsupportedEncodingException, IOException, InterruptedException {
		
		Analyser.findOtherAttachableJavaVMs();
		LOGGER.info("VirtualMachines=" + Analyser.allVirtualMachines);
		
		Thread.sleep(100);
		
		// Create content of response
		final String jsonpModel = model.getJSONPVM().toString();
		
		// For HTTP/1.0 or later send a MIME header
		final String headerString = "HTTP/1.1 200 OK" + NL + "Server: YacaAgent 2.0" + NL + "Content-Type: application/json" + NL
				+ "Content-Length: " + jsonpModel.toString().getBytes("UTF-8").length + NL + NL;
		
		out.write(headerString.getBytes("UTF-8"));
		out.write(jsonpModel.toString().getBytes("UTF-8"));
		out.flush();
	}
	
	private void sendResponsForStaticFile(final OutputStream out, String request, String fileNamePath, String mimeType) throws Exception {
		// Create content of requested file
		InputStream fileContent = getClass().getResourceAsStream("/" + fileNamePath);
		BufferedReader reader = new BufferedReader(new InputStreamReader(fileContent));
		StringBuilder sb = new StringBuilder();
		String line = null;
		while ((line = reader.readLine()) != null) {
			sb.append(line + "\r\n");
		}
		fileContent.close();
		byte[] bytesBody = sb.toString().substring(0, sb.toString().length() - 2).getBytes("UTF-8");
		
		// For HTTP/1.0 or later send a MIME header
		final String headerString = "HTTP/1.1 200 OK" + NL //
				+ "Server: YacaAgent 2.0" + NL //
				+ "Content-Type: " + mimeType + NL//
				+ "Content-Length: " + bytesBody.length + NL + NL;
		byte[] bytesHeader = headerString.getBytes("UTF-8");
		
		out.write(bytesHeader);
		out.write(bytesBody);
		out.flush();
		LOGGER.debug("sent resonse for request=" + request);
	}
	
	private void sendResponse(final OutputStream out, String body) throws IOException {
		final String headerString = "HTTP/1.1 200 OK" + NL + "Server: YacaAgent 2.0" + NL + "Content-Type: application/json" + NL
				+ "Content-Length: " + body.getBytes("UTF-8").length + NL + NL;
		
		out.write(headerString.getBytes("UTF-8"));
		out.write(body.toString().getBytes("UTF-8"));
		out.flush();
		
	}
	
}
