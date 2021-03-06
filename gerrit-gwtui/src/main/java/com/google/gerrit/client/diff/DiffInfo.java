// Copyright (C) 2013 The Android Open Source Project
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

package com.google.gerrit.client.diff;

import com.google.gerrit.client.DiffWebLinkInfo;
import com.google.gerrit.client.WebLinkInfo;
import com.google.gerrit.client.rpc.Natives;
import com.google.gerrit.reviewdb.client.AccountGeneralPreferences.DiffView;
import com.google.gerrit.reviewdb.client.Patch.ChangeType;
import com.google.gwt.core.client.JavaScriptObject;
import com.google.gwt.core.client.JsArray;
import com.google.gwt.core.client.JsArrayString;

import java.util.LinkedList;
import java.util.List;

public class DiffInfo extends JavaScriptObject {
  public static final String GITLINK = "x-git/gitlink";
  public static final String SYMLINK = "x-git/symlink";

  public final native FileMeta meta_a() /*-{ return this.meta_a; }-*/;
  public final native FileMeta meta_b() /*-{ return this.meta_b; }-*/;
  public final native JsArrayString diff_header() /*-{ return this.diff_header; }-*/;
  public final native JsArray<Region> content() /*-{ return this.content; }-*/;
  public final native JsArray<DiffWebLinkInfo> web_links() /*-{ return this.web_links; }-*/;
  public final native boolean binary() /*-{ return this.binary || false; }-*/;

  public final List<WebLinkInfo> side_by_side_web_links() {
    return filterWebLinks(DiffView.SIDE_BY_SIDE);
  }

  public final List<WebLinkInfo> unified_web_links() {
    return filterWebLinks(DiffView.UNIFIED_DIFF);
  }

  private final List<WebLinkInfo> filterWebLinks(DiffView diffView) {
    List<WebLinkInfo> filteredDiffWebLinks = new LinkedList<>();
    List<DiffWebLinkInfo> allDiffWebLinks = Natives.asList(web_links());
    if (allDiffWebLinks != null) {
      for (DiffWebLinkInfo webLink : allDiffWebLinks) {
        if (diffView == DiffView.SIDE_BY_SIDE
            && webLink.showOnSideBySideDiffView()) {
          filteredDiffWebLinks.add(webLink);
        }
        if (diffView == DiffView.UNIFIED_DIFF
            && webLink.showOnUnifiedDiffView()) {
          filteredDiffWebLinks.add(webLink);
        }
      }
    }
    return filteredDiffWebLinks;
  }

  public final ChangeType change_type() {
    return ChangeType.valueOf(change_typeRaw());
  }
  private final native String change_typeRaw()
  /*-{ return this.change_type }-*/;

  public final IntraLineStatus intraline_status() {
    String s = intraline_statusRaw();
    return s != null
        ? IntraLineStatus.valueOf(s)
        : IntraLineStatus.OFF;
  }
  private final native String intraline_statusRaw()
  /*-{ return this.intraline_status }-*/;

  public final boolean has_skip() {
    JsArray<Region> c = content();
    for (int i = 0; i < c.length(); i++) {
      if (c.get(i).skip() != 0) {
        return true;
      }
    }
    return false;
  }

  public final String text_a() {
    StringBuilder s = new StringBuilder();
    JsArray<Region> c = content();
    for (int i = 0; i < c.length(); i++) {
      Region r = c.get(i);
      if (r.ab() != null) {
        append(s, r.ab());
      } else if (r.a() != null) {
        append(s, r.a());
      }
      // TODO skip may need to be handled
    }
    return s.toString();
  }

  public final String text_b() {
    StringBuilder s = new StringBuilder();
    JsArray<Region> c = content();
    for (int i = 0; i < c.length(); i++) {
      Region r = c.get(i);
      if (r.ab() != null) {
        append(s, r.ab());
      } else if (r.b() != null) {
        append(s, r.b());
      }
      // TODO skip may need to be handled
    }
    return s.toString();
  }

  private static void append(StringBuilder s, JsArrayString lines) {
    for (int i = 0; i < lines.length(); i++) {
      s.append(lines.get(i)).append('\n');
    }
  }

  protected DiffInfo() {
  }

  public enum IntraLineStatus {
    OFF, OK, TIMEOUT, FAILURE
  }

  public static class FileMeta extends JavaScriptObject {
    public final native String name() /*-{ return this.name; }-*/;
    public final native String content_type() /*-{ return this.content_type; }-*/;
    public final native int lines() /*-{ return this.lines || 0 }-*/;
    public final native JsArray<WebLinkInfo> web_links() /*-{ return this.web_links; }-*/;

    protected FileMeta() {
    }
  }

  public static class Region extends JavaScriptObject {
    public final native JsArrayString ab() /*-{ return this.ab; }-*/;
    public final native JsArrayString a() /*-{ return this.a; }-*/;
    public final native JsArrayString b() /*-{ return this.b; }-*/;
    public final native int skip() /*-{ return this.skip || 0; }-*/;
    public final native boolean common() /*-{ return this.common || false; }-*/;

    public final native JsArray<Span> edit_a() /*-{ return this.edit_a }-*/;
    public final native JsArray<Span> edit_b() /*-{ return this.edit_b }-*/;

    protected Region() {
    }
  }

  public static class Span extends JavaScriptObject {
    public final native int skip() /*-{ return this[0]; }-*/;
    public final native int mark() /*-{ return this[1]; }-*/;

    protected Span() {
    }
  }
}
