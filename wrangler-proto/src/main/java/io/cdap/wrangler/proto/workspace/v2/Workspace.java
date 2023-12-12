/*
 * Copyright © 2021 Cask Data, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 *
 */

package io.cdap.wrangler.proto.workspace.v2;

import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import javax.annotation.Nullable;

/**
 * Metadata information about a workspace
 */
public class Workspace {
  private final String workspaceName;
  private final String workspaceId;
  private final List<String> directives;
  private final long createdTimeMillis;
  private final long updatedTimeMillis;
  // this is null when the sample does not have a spec, currently only example is directly upload data to wrangler
  private final SampleSpec sampleSpec;
  // this is for insights page in UI
  private final JsonObject insights;

//  private UserDefinedAction userDefinedAction;
//
//  private HashSet<String> nonNullableColumns;

  private HashMap<String, UserDefinedAction> columnMappings;

  private Workspace(String workspaceName, String workspaceId, List<String> directives,
                    long createdTimeMillis, long updatedTimeMillis, @Nullable SampleSpec sampleSpec,
                    JsonObject insights, HashMap<String, UserDefinedAction> columnMappings) {
    this.workspaceName = workspaceName;
    this.workspaceId = workspaceId;
    this.directives = directives;
    this.createdTimeMillis = createdTimeMillis;
    this.updatedTimeMillis = updatedTimeMillis;
    this.sampleSpec = sampleSpec;
    this.insights = insights;
    this.columnMappings = columnMappings == null || columnMappings.isEmpty() ?
        new HashMap<>() : columnMappings;
  }

  public String getWorkspaceName() {
    return workspaceName;
  }

  public String getWorkspaceId() {
    return workspaceId;
  }

  public List<String> getDirectives() {
    return directives;
  }

  public long getCreatedTimeMillis() {
    return createdTimeMillis;
  }

  public long getUpdatedTimeMillis() {
    return updatedTimeMillis;
  }

  @Nullable
  public SampleSpec getSampleSpec() {
    return sampleSpec;
  }

  public JsonObject getInsights() {
    return insights;
  }

//  public HashSet<String> getNonNullableColumns() {
//    return nonNullableColumns;
//  }
//
//  public UserDefinedAction getUserDefinedAction() {
//    return userDefinedAction;
//  }


  public HashMap<String, UserDefinedAction> getColumnMappings() {
    return columnMappings;
  }

  public void setColumnMappings(
      HashMap<String, UserDefinedAction> columnMappings) {
    this.columnMappings = columnMappings;
  }

//  public void setNonNullableColumns(HashSet<String> nonNullableColumns) {
//    this.nonNullableColumns = nonNullableColumns;
//  }
//
//  public void setUserDefinedAction(UserDefinedAction userDefinedAction) {
//    this.userDefinedAction = userDefinedAction;
//  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }

    if (o == null || getClass() != o.getClass()) {
      return false;
    }

    Workspace workspace = (Workspace) o;
    return Objects.equals(workspaceName, workspace.workspaceName) &&
             Objects.equals(workspaceId, workspace.workspaceId) &&
             Objects.equals(directives, workspace.directives) &&
             Objects.equals(sampleSpec, workspace.sampleSpec);
  }

  @Override
  public int hashCode() {
    return Objects.hash(workspaceName, workspaceId, directives, sampleSpec);
  }

  public static Builder builder(String name, String workspaceId) {
    return new Builder(name, workspaceId);
  }

  public static Builder builder(Workspace existing) {
    return new Builder(existing.getWorkspaceName(), existing.getWorkspaceId())
             .setDirectives(existing.getDirectives())
             .setCreatedTimeMillis(existing.getCreatedTimeMillis())
             .setUpdatedTimeMillis(existing.getUpdatedTimeMillis())
             .setSampleSpec(existing.getSampleSpec())
             .setInsights(existing.getInsights())
             .setColumnMappings(existing.getColumnMappings());
  }

  /**
   * Creates Workspace meta objects.
   */
  public static class Builder {
    private final String workspaceName;
    private final String workspaceId;
    private final List<String> directives;
    private long createdTimeMillis;
    private long updatedTimeMillis;
    private SampleSpec sampleSpec;
    private JsonObject insights;
    private HashMap<String, UserDefinedAction> columnMappings;

    Builder(String name, String workspaceId) {
      this.workspaceName = name;
      this.workspaceId = workspaceId;
      this.directives = new ArrayList<>();
      this.insights = new JsonObject();
    }

    public Builder setDirectives(List<String> directives) {
      this.directives.clear();
      this.directives.addAll(directives);
      return this;
    }

    public Builder setCreatedTimeMillis(long createdTimeMillis) {
      this.createdTimeMillis = createdTimeMillis;
      return this;
    }

    public Builder setUpdatedTimeMillis(long updatedTimeMillis) {
      this.updatedTimeMillis = updatedTimeMillis;
      return this;
    }

    public Builder setSampleSpec(SampleSpec sampleSpec) {
      this.sampleSpec = sampleSpec;
      return this;
    }

    public Builder setInsights(JsonObject insights) {
      this.insights = insights;
      return this;
    }

    public Builder setColumnMappings (HashMap<String, UserDefinedAction> columnMappings) {
      this.columnMappings = columnMappings;
      return this;
    }

    public Workspace build() {
      return new Workspace(workspaceName, workspaceId, directives, createdTimeMillis, updatedTimeMillis, sampleSpec,
                           insights, columnMappings);
    }
  }

  /**
   * UserDefinedAction enum.
   */
  public enum UserDefinedAction {
    NO_ACTION,
    SKIP_ROW,
    SEND_TO_ERROR_COLLECTOR,
    ERROR_PIPELINE,
    NULLABLE
  }
}
