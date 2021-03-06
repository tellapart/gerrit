// Copyright (C) 2008 The Android Open Source Project
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.gerrit.httpd.raw;

import static com.google.common.net.HttpHeaders.CONTENT_ENCODING;
import static com.google.common.net.HttpHeaders.ETAG;
import static com.google.common.net.HttpHeaders.IF_NONE_MATCH;
import static com.google.gerrit.common.FileUtil.lastModified;
import static java.util.concurrent.TimeUnit.DAYS;
import static java.util.concurrent.TimeUnit.MINUTES;
import static javax.servlet.http.HttpServletResponse.SC_INTERNAL_SERVER_ERROR;
import static javax.servlet.http.HttpServletResponse.SC_NOT_FOUND;
import static javax.servlet.http.HttpServletResponse.SC_NOT_MODIFIED;

import com.google.common.base.CharMatcher;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.cache.Weigher;
import com.google.common.collect.Maps;
import com.google.common.hash.Hashing;
import com.google.gerrit.common.FileUtil;
import com.google.gerrit.common.Nullable;
import com.google.gerrit.httpd.HtmlDomUtil;
import com.google.gerrit.server.config.GerritServerConfig;
import com.google.gerrit.server.config.SitePaths;
import com.google.gwtexpui.server.CacheHeaders;
import com.google.gwtjsonrpc.server.RPCServletUtils;
import com.google.inject.Inject;
import com.google.inject.Singleton;

import org.eclipse.jgit.lib.Config;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ExecutionException;

import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;


/** Sends static content from the site 's {@code static/} subdirectory. */
@SuppressWarnings("serial")
@Singleton
public class StaticServlet extends HttpServlet {
  private static final Logger log = LoggerFactory.getLogger(StaticServlet.class);
  private static final String JS = "application/x-javascript";
  private static final Map<String, String> MIME_TYPES = Maps.newHashMap();
  static {
    MIME_TYPES.put("html", "text/html");
    MIME_TYPES.put("htm", "text/html");
    MIME_TYPES.put("js", JS);
    MIME_TYPES.put("css", "text/css");
    MIME_TYPES.put("rtf", "text/rtf");
    MIME_TYPES.put("txt", "text/plain");
    MIME_TYPES.put("text", "text/plain");
    MIME_TYPES.put("pdf", "application/pdf");
    MIME_TYPES.put("jpeg", "image/jpeg");
    MIME_TYPES.put("jpg", "image/jpeg");
    MIME_TYPES.put("gif", "image/gif");
    MIME_TYPES.put("png", "image/png");
    MIME_TYPES.put("tiff", "image/tiff");
    MIME_TYPES.put("tif", "image/tiff");
    MIME_TYPES.put("svg", "image/svg+xml");
  }

  private static String contentType(final String name) {
    final int dot = name.lastIndexOf('.');
    final String ext = 0 < dot ? name.substring(dot + 1) : "";
    final String type = MIME_TYPES.get(ext);
    return type != null ? type : "application/octet-stream";
  }

  private final Path staticBase;
  private final boolean refresh;
  private final LoadingCache<String, Resource> cache;

  @Inject
  StaticServlet(@GerritServerConfig Config cfg, SitePaths site) {
    Path p;
    try {
      p = site.static_dir.toRealPath().normalize();
    } catch (IOException e) {
      p = site.static_dir.toAbsolutePath().normalize();
    }
    staticBase = p;
    refresh = cfg.getBoolean("site", "refreshHeaderFooter", true);
    cache = CacheBuilder.newBuilder()
        .maximumWeight(1 << 20)
        .weigher(new Weigher<String, Resource>() {
          @Override
          public int weigh(String name, Resource r) {
            return 2 * name.length() + r.raw.length;
          }
        })
        .build(new CacheLoader<String, Resource>() {
          @Override
          public Resource load(String name) throws Exception {
            return loadResource(name);
          }
        });
  }

  @Nullable
  Resource getResource(String name) {
    try {
      return cache.get(name);
    } catch (ExecutionException e) {
      log.warn(String.format("Cannot load static resource %s", name), e);
      return null;
    }
  }

  private Resource getResource(HttpServletRequest req)
      throws ExecutionException {
    String name = CharMatcher.is('/').trimFrom(req.getPathInfo());
    if (isUnreasonableName(name)) {
      return Resource.NOT_FOUND;
    }

    Resource r = cache.get(name);
    if (r == Resource.NOT_FOUND) {
      return Resource.NOT_FOUND;
    }

    if (refresh && r.isStale()) {
      cache.invalidate(name);
      r = cache.get(name);
    }
    return r;
  }

  private static boolean isUnreasonableName(String name) {
    return name.length() < 1
      || name.contains("\\") // no windows/dos style paths
      || name.startsWith("../") // no "../etc/passwd"
      || name.contains("/../") // no "foo/../etc/passwd"
      || name.contains("/./") // "foo/./foo" is insane to ask
      || name.contains("//"); // windows UNC path can be "//..."
  }

  @Override
  protected void doGet(final HttpServletRequest req,
      final HttpServletResponse rsp) throws IOException {
    Resource r;
    try {
      r = getResource(req);
    } catch (ExecutionException e) {
      log.warn(String.format(
          "Cannot load static resource %s",
          req.getPathInfo()), e);
      CacheHeaders.setNotCacheable(rsp);
      rsp.setStatus(SC_INTERNAL_SERVER_ERROR);
      return;
    }

    String e = req.getParameter("e");
    if (r == Resource.NOT_FOUND || (e != null && !r.etag.equals(e))) {
      CacheHeaders.setNotCacheable(rsp);
      rsp.setStatus(SC_NOT_FOUND);
      return;
    } else if (r.etag.equals(req.getHeader(IF_NONE_MATCH))) {
      rsp.setStatus(SC_NOT_MODIFIED);
      return;
    }

    byte[] tosend = r.raw;
    if (!r.contentType.equals(JS) && RPCServletUtils.acceptsGzipEncoding(req)) {
      byte[] gz = HtmlDomUtil.compress(tosend);
      if ((gz.length + 24) < tosend.length) {
        rsp.setHeader(CONTENT_ENCODING, "gzip");
        tosend = gz;
      }
    }
    if (e != null && r.etag.equals(e)) {
      CacheHeaders.setCacheable(req, rsp, 360, DAYS, false);
    } else {
      CacheHeaders.setCacheable(req, rsp, 15, MINUTES, refresh);
    }
    rsp.setHeader(ETAG, r.etag);
    rsp.setContentType(r.contentType);
    rsp.setContentLength(tosend.length);
    final OutputStream out = rsp.getOutputStream();
    try {
      out.write(tosend);
    } finally {
      out.close();
    }
  }

  private Resource loadResource(String name) throws IOException {
    Path p = staticBase.resolve(name);
    try {
      p = p.toRealPath().normalize();
    } catch (IOException e) {
      return Resource.NOT_FOUND;
    }
    if (!p.startsWith(staticBase)) {
      return Resource.NOT_FOUND;
    }

    long ts = FileUtil.lastModified(p);
    byte[] raw;
    try {
      raw = Files.readAllBytes(p);
    } catch (NoSuchFileException e) {
      return Resource.NOT_FOUND;
    }
    return new Resource(p, ts, contentType(name), raw);
  }

  static class Resource {
    static final Resource NOT_FOUND = new Resource(null, -1, "", new byte[] {});

    final Path src;
    final long lastModified;
    final String contentType;
    final String etag;
    final byte[] raw;

    Resource(Path src, long lastModified, String contentType, byte[] raw) {
      this.src = src;
      this.lastModified = lastModified;
      this.contentType = contentType;
      this.etag = Hashing.md5().hashBytes(raw).toString();
      this.raw = raw;
    }

    boolean isStale() {
      return lastModified != lastModified(src);
    }
  }
}
