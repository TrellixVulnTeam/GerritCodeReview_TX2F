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

package com.google.gerrit.server.query.change;

import com.google.gerrit.common.data.LabelType;
import com.google.gerrit.common.data.LabelTypes;
import com.google.gerrit.common.data.Permission;
import com.google.gerrit.reviewdb.client.Account;
import com.google.gerrit.reviewdb.client.Change;
import com.google.gerrit.reviewdb.client.PatchSetApproval;
import com.google.gerrit.reviewdb.server.ReviewDb;
import com.google.gerrit.server.IdentifiedUser;
import com.google.gerrit.server.index.ChangeField;
import com.google.gerrit.server.index.IndexPredicate;
import com.google.gerrit.server.project.ChangeControl;
import com.google.gerrit.server.project.NoSuchChangeException;
import com.google.gerrit.server.project.ProjectCache;
import com.google.gerrit.server.project.ProjectState;
import com.google.gwtorm.server.OrmException;
import com.google.inject.Provider;

class EqualsLabelPredicate extends IndexPredicate<ChangeData> {
  private final ProjectCache projectCache;
  private final ChangeControl.GenericFactory ccFactory;
  private final IdentifiedUser.GenericFactory userFactory;
  private final Provider<ReviewDb> dbProvider;
  private final String label;
  private final int expVal;

  EqualsLabelPredicate(ProjectCache projectCache,
      ChangeControl.GenericFactory ccFactory,
      IdentifiedUser.GenericFactory userFactory, Provider<ReviewDb> dbProvider,
      String label, int expVal) {
    super(ChangeField.LABEL, ChangeField.formatLabel(label, expVal));
    this.ccFactory = ccFactory;
    this.projectCache = projectCache;
    this.userFactory = userFactory;
    this.dbProvider = dbProvider;
    this.label = label;
    this.expVal = expVal;
  }

  @Override
  public boolean match(ChangeData object) throws OrmException {
    Change c = object.change(dbProvider);
    if (c == null) {
      // The change has disappeared.
      //
      return false;
    }
    ProjectState project = projectCache.get(c.getDest().getParentKey());
    if (project == null) {
      // The project has disappeared.
      //
      return false;
    }
    LabelType labelType = type(project.getLabelTypes(), label);
    boolean hasVote = false;
    for (PatchSetApproval p : object.currentApprovals(dbProvider)) {
      if (labelType.matches(p)) {
        hasVote = true;
        if (match(c, p.getValue(), p.getAccountId(), labelType)) {
          return true;
        }
      }
    }

    if (!hasVote && expVal == 0) {
      return true;
    }

    return false;
  }

  private static LabelType type(LabelTypes types, String toFind) {
    if (types.byLabel(toFind) != null) {
      return types.byLabel(toFind);
    }

    for (LabelType lt : types.getLabelTypes()) {
      if (toFind.equalsIgnoreCase(lt.getName())) {
        return lt;
      }
    }

    for (LabelType lt : types.getLabelTypes()) {
      if (toFind.equalsIgnoreCase(lt.getAbbreviation())) {
        return lt;
      }
    }

    return LabelType.withDefaultValues(toFind);
  }

  private boolean match(Change change, int value, Account.Id approver,
      LabelType type) throws OrmException {
    int psVal = value;
    if (psVal == expVal) {
      // Double check the value is still permitted for the user.
      //
      try {
        ChangeControl cc = ccFactory.controlFor(change, //
            userFactory.create(dbProvider, approver));
        if (!cc.isVisible(dbProvider.get())) {
          // The user can't see the change anymore.
          //
          return false;
        }
        psVal = cc.getRange(Permission.forLabel(type.getName())).squash(psVal);
      } catch (NoSuchChangeException e) {
        // The project has disappeared.
        //
        return false;
      }

      if (psVal == expVal) {
        return true;
      }
    }
    return false;
  }

  @Override
  public int getCost() {
    return 1;
  }
}
