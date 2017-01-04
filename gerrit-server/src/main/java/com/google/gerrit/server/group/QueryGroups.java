// Copyright (C) 2017 The Android Open Source Project
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

package com.google.gerrit.server.group;

import com.google.common.base.Strings;
import com.google.common.collect.Lists;
import com.google.gerrit.common.data.GroupDescriptions;
import com.google.gerrit.extensions.client.ListGroupsOption;
import com.google.gerrit.extensions.common.GroupInfo;
import com.google.gerrit.extensions.restapi.BadRequestException;
import com.google.gerrit.extensions.restapi.MethodNotAllowedException;
import com.google.gerrit.extensions.restapi.RestReadView;
import com.google.gerrit.extensions.restapi.TopLevelResource;
import com.google.gerrit.reviewdb.client.AccountGroup;
import com.google.gerrit.server.index.group.GroupIndex;
import com.google.gerrit.server.index.group.GroupIndexCollection;
import com.google.gerrit.server.query.QueryParseException;
import com.google.gerrit.server.query.group.GroupQueryBuilder;
import com.google.gerrit.server.query.group.GroupQueryProcessor;
import com.google.gwtorm.server.OrmException;
import com.google.inject.Inject;

import org.kohsuke.args4j.Option;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

public class QueryGroups implements RestReadView<TopLevelResource> {
  private final GroupIndexCollection indexes;
  private final GroupQueryBuilder queryBuilder;
  private final GroupQueryProcessor queryProcessor;
  private final GroupJson json;

  private String query;
  private int limit;
  private int start;
  private EnumSet<ListGroupsOption> options =
      EnumSet.noneOf(ListGroupsOption.class);

  /** --query (-q) is already used by {@link ListGroups} */
  @Option(name = "--query2", aliases = {"-q2"}, usage = "group query")
  public void setQuery(String query) {
    this.query = query;
  }

  @Option(name = "--limit", aliases = {"-n"}, metaVar = "CNT",
      usage = "maximum number of groups to list")
  public void setLimit(int limit) {
    this.limit = limit;
  }

  @Option(name = "--start", aliases = {"-S"}, metaVar = "CNT",
      usage = "number of groups to skip")
  public void setStart(int start) {
    this.start = start;
  }

  @Option(name = "-o", usage = "Output options per group")
  public void addOption(ListGroupsOption o) {
    options.add(o);
  }

  @Option(name = "-O", usage = "Output option flags, in hex")
  public void setOptionFlagsHex(String hex) {
    options.addAll(ListGroupsOption.fromBits(Integer.parseInt(hex, 16)));
  }

  @Inject
  protected QueryGroups(GroupIndexCollection indexes,
      GroupQueryBuilder queryBuilder,
      GroupQueryProcessor queryProcessor,
      GroupJson json) {
    this.indexes = indexes;
    this.queryBuilder = queryBuilder;
    this.queryProcessor = queryProcessor;
    this.json = json;
  }

  @Override
  public List<GroupInfo> apply(TopLevelResource resource)
      throws BadRequestException, MethodNotAllowedException, OrmException {
    if (Strings.isNullOrEmpty(query)) {
      throw new BadRequestException("missing query field");
    }

    GroupIndex searchIndex = indexes.getSearchIndex();
    if (searchIndex == null) {
      throw new MethodNotAllowedException("no group index");
    }

    if (start != 0) {
      queryProcessor.setStart(start);
    }

    if (limit != 0) {
      queryProcessor.setLimit(limit);
    }

    try {
      List<AccountGroup> result =
          queryProcessor.query(queryBuilder.parse(query)).entities();

      ArrayList<GroupInfo> groupInfos =
          Lists.newArrayListWithCapacity(result.size());
      json.addOptions(options);
      for (AccountGroup group : result) {
        groupInfos.add(json.format(GroupDescriptions.forAccountGroup(group)));
      }
      return groupInfos;
    } catch (QueryParseException e) {
      throw new BadRequestException(e.getMessage());
    }
  }
}
