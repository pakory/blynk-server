/*
 * Copyright 2012 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package cc.blynk.core.http.handlers;

import cc.blynk.core.http.Response;
import cc.blynk.server.core.protocol.handlers.DefaultExceptionHandler;
import cc.blynk.utils.ServerProperties;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.*;
import io.netty.handler.codec.http.multipart.*;
import io.netty.handler.codec.http.multipart.HttpPostRequestDecoder.EndOfDataDecoderException;
import io.netty.handler.codec.http.multipart.HttpPostRequestDecoder.ErrorDataDecoderException;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.nio.file.*;

import static cc.blynk.core.http.Response.*;

public class UploadHandler extends SimpleChannelInboundHandler<HttpObject> implements DefaultExceptionHandler {

    private static final Logger log = LogManager.getLogger(UploadHandler.class);

    static final HttpDataFactory factory = new DefaultHttpDataFactory(true);
    final String handlerUri;
    HttpPostRequestDecoder decoder;
    final String baseDir;
    final String uploadFolder;
    String uri;

    public UploadHandler(String handlerUri, String uploadFolder) {
        super(false);
        this.handlerUri = handlerUri;
        this.baseDir = ServerProperties.jarPath;
        this.uploadFolder = uploadFolder.endsWith("/") ? uploadFolder : uploadFolder + "/";
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        if (decoder != null) {
            decoder.cleanFiles();
        }
    }

    public boolean accept(HttpRequest req) {
        return req.method() == HttpMethod.POST && req.uri().startsWith(handlerUri);
    }

    @Override
    public void channelRead0(ChannelHandlerContext ctx, HttpObject msg) throws Exception {
        if (msg instanceof HttpRequest) {
            HttpRequest req = (HttpRequest) msg;

            uri = req.uri();
            if (!accept(req)) {
                ctx.fireChannelRead(msg);
                return;
            }

            try {
                log.debug("Incoming {} {}", req.method(), req.uri());
                decoder = new HttpPostRequestDecoder(factory, req);
            } catch (ErrorDataDecoderException e) {
                log.error("Error creating http post request decoder.", e);
                ctx.writeAndFlush(badRequest(e.getMessage()));
                return;
            }

        }

        if (decoder != null && msg instanceof HttpContent) {
                // New chunk is received
            HttpContent chunk = (HttpContent) msg;
            try {
                decoder.offer(chunk);
            } catch (ErrorDataDecoderException e) {
                log.error("Error creating http post offer.", e);
                ctx.writeAndFlush(badRequest(e.getMessage()));
                return;
            } finally {
                chunk.release();
            }

            // example of reading only if at the end
            if (chunk instanceof LastHttpContent) {
                Response response;
                try {
                    String path = finishUpload();
                    if (path != null) {
                        response = afterUpload(path);
                    } else {
                        response = serverError();
                    }
                } catch (NoSuchFileException e) {
                    log.error("Unable to copy uploaded file to static folder. Reason : {}", e.getMessage());
                    response = (serverError());
                } catch (Exception e) {
                    log.error("Error during file upload.", e);
                    response = (serverError());
                }
                ctx.writeAndFlush(response);
            }
        }
    }

    public Response afterUpload(String path) {
        return ok(path);
    }

    private String finishUpload() throws Exception{
        String pathTo = null;
        try {
            while (decoder.hasNext()) {
                InterfaceHttpData data = decoder.next();
                if (data != null) {
                    if (data instanceof DiskFileUpload) {
                        DiskFileUpload diskFileUpload = (DiskFileUpload) data;
                        Path tmpFile = diskFileUpload.getFile().toPath();
                        String uploadedFilename = diskFileUpload.getFilename();
                        String extension = "";
                        if (uploadedFilename.contains(".")) {
                            extension = uploadedFilename.substring(uploadedFilename.lastIndexOf("."), uploadedFilename.length());
                        }
                        String finalName = tmpFile.getFileName().toString() + extension;

                        //this is just to make it work on team city.
                        Path staticPath = Paths.get(baseDir, uploadFolder);
                        if (!Files.exists(staticPath)) {
                            Files.createDirectories(staticPath);
                        }

                        Files.move(tmpFile, Paths.get(baseDir, uploadFolder, finalName), StandardCopyOption.REPLACE_EXISTING);
                        pathTo =  uploadFolder + finalName;
                    }
                    data.release();
                }
            }
        } catch (EndOfDataDecoderException endOfData) {
            //ignore. that's fine.
        } finally {
            // destroy the decoder to release all resources
            decoder.destroy();
            decoder = null;
        }

        return pathTo;
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        handleGeneralException(ctx, cause);
    }
}
