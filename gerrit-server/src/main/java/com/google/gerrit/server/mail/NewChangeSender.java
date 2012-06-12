// Copyright (C) 2009 The Android Open Source Project
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

package com.google.gerrit.server.mail;

import com.google.gerrit.reviewdb.client.Account;
import com.google.gerrit.reviewdb.client.Change;
import com.google.gerrit.server.patch.PatchList;
import com.google.gerrit.server.patch.PatchListNotAvailableException;
import com.google.gerrit.server.ssh.SshInfo;

import com.jcraft.jsch.HostKey;

import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.errors.RepositoryNotFoundException;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.util.RawParseUtils;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Sends an email alerting a user to a new change for them to review. */
public abstract class NewChangeSender extends ChangeEmail {
  private static final Logger log =
      LoggerFactory.getLogger(NewChangeSender.class);

  private final SshInfo sshInfo;
  private final Set<Account.Id> reviewers = new HashSet<Account.Id>();
  private final Set<Account.Id> extraCC = new HashSet<Account.Id>();

  protected NewChangeSender(EmailArguments ea, String anonymousCowardName,
      SshInfo sshInfo, Change c) {
    super(ea, anonymousCowardName, c, "newchange");
    this.sshInfo = sshInfo;
  }

  public void addReviewers(final Collection<Account.Id> cc) {
    reviewers.addAll(cc);
  }

  public void addExtraCC(final Collection<Account.Id> cc) {
    extraCC.addAll(cc);
  }

  @Override
  protected void init() throws EmailException {
    super.init();

    setHeader("Message-ID", getChangeMessageThreadId());

    add(RecipientType.TO, reviewers);
    add(RecipientType.CC, extraCC);
    rcptToAuthors(RecipientType.CC);
  }

  @Override
  protected void formatChange() throws EmailException {
    appendText(velocifyFile("NewChange.vm"));
  }

  public List<String> getReviewerNames() {
    if (reviewers.isEmpty()) {
      return null;
    }
    List<String> names = new ArrayList<String>();
    for (Account.Id id : reviewers) {
      names.add(getNameFor(id));
    }
    return names;
  }

  public String getSshHost() {
    final List<HostKey> hostKeys = sshInfo.getHostKeys();
    if (hostKeys.isEmpty()) {
      return null;
    }

    final String host = hostKeys.get(0).getHost();
    if (host.startsWith("*:")) {
      return getGerritHost() + host.substring(1);
    }
    return host;
  }

  public boolean getIncludeDiff() {
    return args.settings.includeDiff;
  }

  /** Show patch set as unified difference.  */
  public String getUnifiedDiff() {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    Repository repo = getRepository();
    if (repo != null) {
      DiffFormatter df = new DiffFormatter(out);
      try {
        PatchList patchList = getPatchList();
        if (patchList.getOldId() != null) {
          df.setRepository(repo);
          df.setDetectRenames(true);
          df.format(patchList.getOldId(), patchList.getNewId());
        }
      } catch (PatchListNotAvailableException e) {
        log.error("Cannot format patch", e);
      } catch (IOException e) {
        log.error("Cannot format patch", e);
      } finally {
        df.release();
        repo.close();
      }
    }
    return RawParseUtils.decode(out.toByteArray());
  }

  private Repository getRepository() {
    try {
      return args.server.openRepository(change.getProject());
    } catch (RepositoryNotFoundException e) {
      log.error("Cannot open repository", e);
      return null;
    } catch (IOException e) {
      log.error("Cannot open repository", e);
      return null;
    }
  }
}
