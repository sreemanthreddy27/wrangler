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

package io.cdap.wrangler.service.directive;

import com.google.common.base.Strings;
import com.google.common.collect.ImmutableMap;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import io.cdap.cdap.api.NamespaceSummary;
import io.cdap.cdap.api.annotation.TransactionControl;
import io.cdap.cdap.api.annotation.TransactionPolicy;
import io.cdap.cdap.api.artifact.ArtifactSummary;
import io.cdap.cdap.api.data.format.StructuredRecord;
import io.cdap.cdap.api.data.schema.Schema;
import io.cdap.cdap.api.metrics.Metrics;
import io.cdap.cdap.api.service.http.HttpServiceRequest;
import io.cdap.cdap.api.service.http.HttpServiceResponder;
import io.cdap.cdap.api.service.http.SystemHttpServiceContext;
import io.cdap.cdap.api.service.worker.RunnableTaskRequest;
import io.cdap.cdap.etl.api.connector.SampleRequest;
import io.cdap.cdap.etl.common.Constants;
import io.cdap.cdap.etl.proto.ArtifactSelectorConfig;
import io.cdap.cdap.etl.proto.connection.ConnectorDetail;
import io.cdap.cdap.etl.proto.connection.SampleResponse;
import io.cdap.cdap.internal.io.SchemaTypeAdapter;
import io.cdap.cdap.proto.id.NamespaceId;
import io.cdap.wrangler.PropertyIds;
import io.cdap.wrangler.RequestExtractor;
import io.cdap.wrangler.api.DirectiveConfig;
import io.cdap.wrangler.api.DirectiveExecutionException;
import io.cdap.wrangler.api.DirectiveLoadException;
import io.cdap.wrangler.api.DirectiveParseException;
import io.cdap.wrangler.api.ErrorRowException;
import io.cdap.wrangler.api.GrammarMigrator;
import io.cdap.wrangler.api.RecipeException;
import io.cdap.wrangler.api.Row;
import io.cdap.wrangler.api.TransientVariableScope;
import io.cdap.wrangler.parser.ConfigDirectiveContext;
import io.cdap.wrangler.parser.DirectiveClass;
import io.cdap.wrangler.parser.GrammarWalker;
import io.cdap.wrangler.parser.MigrateToV2;
import io.cdap.wrangler.parser.RecipeCompiler;
import io.cdap.wrangler.proto.BadRequestException;
import io.cdap.wrangler.proto.recipe.v2.Recipe;
import io.cdap.wrangler.proto.recipe.v2.RecipeId;
import io.cdap.wrangler.proto.workspace.v2.Artifact;
import io.cdap.wrangler.proto.workspace.v2.DirectiveExecutionRequest;
import io.cdap.wrangler.proto.workspace.v2.DirectiveExecutionResponse;
import io.cdap.wrangler.proto.workspace.v2.DirectiveUsage;
import io.cdap.wrangler.proto.workspace.v2.Plugin;
import io.cdap.wrangler.proto.workspace.v2.SampleSpec;
import io.cdap.wrangler.proto.workspace.v2.ServiceResponse;
import io.cdap.wrangler.proto.workspace.v2.StageSpec;
import io.cdap.wrangler.proto.workspace.v2.Workspace;
import io.cdap.wrangler.proto.workspace.v2.Workspace.UserDefinedAction;
import io.cdap.wrangler.proto.workspace.v2.WorkspaceCreationRequest;
import io.cdap.wrangler.proto.workspace.v2.WorkspaceDetail;
import io.cdap.wrangler.proto.workspace.v2.WorkspaceId;
import io.cdap.wrangler.proto.workspace.v2.WorkspaceSpec;
import io.cdap.wrangler.proto.workspace.v2.WorkspaceUpdateRequest;
import io.cdap.wrangler.registry.DirectiveInfo;
import io.cdap.wrangler.registry.SystemDirectiveRegistry;
import io.cdap.wrangler.schema.TransientStoreKeys;
import io.cdap.wrangler.store.recipe.RecipeStore;
import io.cdap.wrangler.store.workspace.WorkspaceStore;
import io.cdap.wrangler.utils.ObjectSerDe;
import io.cdap.wrangler.utils.RowHelper;
import io.cdap.wrangler.utils.SchemaConverter;
import io.cdap.wrangler.utils.StructuredToRowTransformer;
import org.apache.commons.lang3.StringEscapeUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import javax.annotation.Nullable;
import javax.ws.rs.DELETE;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;

/**
 * V2 endpoints for workspace
 */
public class WorkspaceHandler extends AbstractDirectiveHandler {

  private static final Gson GSON =
    new GsonBuilder().registerTypeAdapter(Schema.class, new SchemaTypeAdapter()).create();
  private static final Logger LOG = LoggerFactory.getLogger(WorkspaceHandler.class);
  private static final Pattern PRAGMA_PATTERN = Pattern.compile("^\\s*#pragma\\s+load-directives\\s+");
  private static final String UPLOAD_COUNT = "upload.file.count";
  private static final String CONNECTION_TYPE = "upload";

  private WorkspaceStore wsStore;
  private RecipeStore recipeStore;
  private ConnectionDiscoverer discoverer;

  // Injected by CDAP
  @SuppressWarnings("unused")
  private Metrics metrics;

  @Override
  public void initialize(SystemHttpServiceContext context) throws Exception {
    super.initialize(context);
    wsStore = new WorkspaceStore(context);
    recipeStore = new RecipeStore(context);
    discoverer = new ConnectionDiscoverer(context);
  }

  @POST
  @TransactionPolicy(value = TransactionControl.EXPLICIT)
  @Path("v2/contexts/{context}/workspaces")
  public void createWorkspace(HttpServiceRequest request, HttpServiceResponder responder,
                              @PathParam("context") String namespace) {
    respond(responder, namespace, ns -> {
      if (ns.getName().equalsIgnoreCase(NamespaceId.SYSTEM.getNamespace())) {
        throw new BadRequestException("Creating workspace in system namespace is currently not supported");
      }

      WorkspaceCreationRequest creationRequest =
        GSON.fromJson(StandardCharsets.UTF_8.decode(request.getContent()).toString(), WorkspaceCreationRequest.class);

      if (creationRequest.getConnection() == null) {
        throw new BadRequestException("Connection name has to be provided to create a workspace");
      }

      SampleRequest sampleRequest = creationRequest.getSampleRequest();
      if (sampleRequest == null) {
        throw new BadRequestException("Sample request has to be provided to create a workspace");
      }

      SampleResponse sampleResponse = discoverer.retrieveSample(namespace, creationRequest.getConnection(),
                                                                sampleRequest);
      List<Row> rows = getSample(sampleResponse);

      ConnectorDetail detail = sampleResponse.getDetail();
      SampleSpec spec = new SampleSpec(
        creationRequest.getConnection(), creationRequest.getConnectionType(), sampleRequest.getPath(),
        detail.getRelatedPlugins().stream().map(plugin -> {
          ArtifactSelectorConfig artifact = plugin.getArtifact();
          Plugin pluginSpec = new Plugin(
            plugin.getName(), plugin.getType(), plugin.getProperties(),
            new Artifact(artifact.getName(), artifact.getVersion(), artifact.getScope()));
          return new StageSpec(plugin.getSchema(), pluginSpec);
        }).collect(Collectors.toSet()), detail.getSupportedSampleTypes(), sampleRequest);

      WorkspaceId wsId = new WorkspaceId(ns);
      long now = System.currentTimeMillis();
      Workspace workspace = Workspace.builder(generateWorkspaceName(wsId, creationRequest.getSampleRequest().getPath()),
                                              wsId.getWorkspaceId())
                              .setCreatedTimeMillis(now).setUpdatedTimeMillis(now)
          .setSampleSpec(spec).setColumnMappings(new HashMap<>()).build();
      wsStore.saveWorkspace(wsId, new WorkspaceDetail(workspace, rows));
      responder.sendJson(wsId.getWorkspaceId());
    });
  }

  @GET
  @TransactionPolicy(value = TransactionControl.EXPLICIT)
  @Path("v2/contexts/{context}/workspaces")
  public void listWorkspaces(HttpServiceRequest request, HttpServiceResponder responder,
                             @PathParam("context") String namespace) {
    respond(responder, namespace, ns -> {
      if (ns.getName().equalsIgnoreCase(NamespaceId.SYSTEM.getNamespace())) {
        throw new BadRequestException("Listing workspaces in system namespace is currently not supported");
      }
      responder.sendString(GSON.toJson(new ServiceResponse<>(wsStore.listWorkspaces(ns))));
    });
  }

  @GET
  @TransactionPolicy(value = TransactionControl.EXPLICIT)
  @Path("v2/contexts/{context}/workspaces/{id}")
  public void getWorkspace(HttpServiceRequest request, HttpServiceResponder responder,
                           @PathParam("context") String namespace,
                           @PathParam("id") String workspaceId) {
    respond(responder, namespace, ns -> {
      if (ns.getName().equalsIgnoreCase(NamespaceId.SYSTEM.getNamespace())) {
        throw new BadRequestException("Getting workspace in system namespace is currently not supported");
      }
      responder.sendString(GSON.toJson(wsStore.getWorkspace(new WorkspaceId(ns, workspaceId))));
    });
  }

  /**
   * Update the workspace's directives and initiatives
   */
  @POST
  @TransactionPolicy(value = TransactionControl.EXPLICIT)
  @Path("v2/contexts/{context}/workspaces/{id}")
  public void updateWorkspace(HttpServiceRequest request, HttpServiceResponder responder,
                              @PathParam("context") String namespace,
                              @PathParam("id") String workspaceId) {
    respond(responder, namespace, ns -> {
      if (ns.getName().equalsIgnoreCase(NamespaceId.SYSTEM.getNamespace())) {
        throw new BadRequestException("Updating workspace in system namespace is currently not supported");
      }

      WorkspaceUpdateRequest updateRequest =
        GSON.fromJson(StandardCharsets.UTF_8.decode(request.getContent()).toString(), WorkspaceUpdateRequest.class);

      WorkspaceId wsId = new WorkspaceId(ns, workspaceId);
      Workspace newWorkspace = Workspace.builder(wsStore.getWorkspace(wsId))
                                 .setDirectives(updateRequest.getDirectives())
                                 .setInsights(updateRequest.getInsights())
                                 .setUpdatedTimeMillis(System.currentTimeMillis()).build();
      wsStore.updateWorkspace(wsId, newWorkspace);
      responder.sendStatus(HttpURLConnection.HTTP_OK);
    });
  }

  /**
   * Resample the workspace using a new sample request. Keeps all previously-applied directives.
   */
  @POST
  @TransactionPolicy(value = TransactionControl.EXPLICIT)
  @Path("v2/contexts/{context}/workspaces/{id}/resample")
  public void resampleWorkspace(HttpServiceRequest request, HttpServiceResponder responder,
                                @PathParam("context") String namespace,
                                @PathParam("id") String workspaceId) {
    respond(responder, namespace, ns -> {
      if (ns.getName().equalsIgnoreCase(NamespaceId.SYSTEM.getNamespace())) {
        throw new BadRequestException("Resampling workspace in system namespace is currently not supported");
      }

      WorkspaceId wsId = new WorkspaceId(ns, workspaceId);
      Workspace currentWorkspace = wsStore.getWorkspace(wsId);

      String connectionName = currentWorkspace.getSampleSpec() == null ? null :
        currentWorkspace.getSampleSpec().getConnectionName();
      if (connectionName == null) {
        throw new BadRequestException("Connection name has to exist to resample a workspace");
      }

      String sampleRequestString = StandardCharsets.UTF_8.decode(request.getContent()).toString();
      // sampleRequestString looks like {"sampleRequest": {...}}, so to parse it into SampleRequest.class
      // we first have to extract the inner object
      JsonElement sampleRequestJson =
        new JsonParser().parse(sampleRequestString).getAsJsonObject().get("sampleRequest");
      SampleRequest sampleRequest = GSON.fromJson(sampleRequestJson, SampleRequest.class);
      if (sampleRequest == null) {
        throw new BadRequestException("Sample request has to be provided to resample a workspace");
      }

      SampleResponse sampleResponse = discoverer.retrieveSample(namespace, connectionName,
                                                                sampleRequest);
      List<Row> rows = getSample(sampleResponse);

      SampleSpec oldSpec = currentWorkspace.getSampleSpec();
      SampleSpec newSpec = new SampleSpec(oldSpec.getConnectionName(), oldSpec.getConnectionType(), oldSpec.getPath(),
              oldSpec.getRelatedPlugins(), oldSpec.getSupportedSampleTypes(), sampleRequest);

      Workspace newWorkspace = Workspace.builder(currentWorkspace)
        .setUpdatedTimeMillis(System.currentTimeMillis())
        .setSampleSpec(newSpec).build();
      wsStore.saveWorkspace(wsId, new WorkspaceDetail(newWorkspace, rows));
      responder.sendStatus(HttpURLConnection.HTTP_OK);
    });
  }

  @DELETE
  @TransactionPolicy(value = TransactionControl.EXPLICIT)
  @Path("v2/contexts/{context}/workspaces/{id}")
  public void deleteWorkspace(HttpServiceRequest request, HttpServiceResponder responder,
                              @PathParam("context") String namespace,
                              @PathParam("id") String workspaceId) {
    respond(responder, namespace, ns -> {
      if (ns.getName().equalsIgnoreCase(NamespaceId.SYSTEM.getNamespace())) {
        throw new BadRequestException("Deleting workspace in system namespace is currently not supported");
      }
      wsStore.deleteWorkspace(new WorkspaceId(ns, workspaceId));
      responder.sendStatus(HttpURLConnection.HTTP_OK);
    });
  }

  /**
   * Upload data to the workspace, the workspace is created automatically on fly.
   */
  @POST
  @TransactionPolicy(value = TransactionControl.EXPLICIT)
  @Path("v2/contexts/{context}/workspaces/upload")
  public void upload(HttpServiceRequest request, HttpServiceResponder responder,
                     @PathParam("context") String namespace) {
    respond(responder, namespace, ns -> {
      if (ns.getName().equalsIgnoreCase(NamespaceId.SYSTEM.getNamespace())) {
        throw new BadRequestException("Uploading data in system namespace is currently not supported");
      }

      String name = request.getHeader(PropertyIds.FILE_NAME);
      if (name == null) {
        throw new BadRequestException("Name must be provided in the 'file' header");
      }

      RequestExtractor handler = new RequestExtractor(request);

      // For back-ward compatibility, we check if there is delimiter specified
      // using 'recorddelimiter' or 'delimiter'
      String delimiter = handler.getHeader(RECORD_DELIMITER_HEADER, "\\u001A");
      delimiter = handler.getHeader(DELIMITER_HEADER, delimiter);
      String content = handler.getContent(StandardCharsets.UTF_8);
      if (content == null) {
        throw new BadRequestException(
          "Body not present, please post the file containing the records to create a workspace.");
      }

      delimiter = StringEscapeUtils.unescapeJava(delimiter);
      List<Row> sample = new ArrayList<>();
      for (String line : content.split(delimiter)) {
        sample.add(new Row(COLUMN_NAME, line));
      }

      WorkspaceId id = new WorkspaceId(ns);
      long now = System.currentTimeMillis();
      Workspace workspace = Workspace.builder(name, id.getWorkspaceId())
                              .setCreatedTimeMillis(now).setUpdatedTimeMillis(now).build();
      wsStore.saveWorkspace(id, new WorkspaceDetail(workspace, sample));
      Metrics child = metrics.child(ImmutableMap.of(Constants.Metrics.Tag.APP_ENTITY_TYPE,
                                                    Constants.CONNECTION_SERVICE_NAME,
                                                    Constants.Metrics.Tag.APP_ENTITY_TYPE_NAME,
                                                    CONNECTION_TYPE));
      child.count(UPLOAD_COUNT, 1);
      responder.sendJson(id.getWorkspaceId());
    });
  }

  /**
   * Executes the directives on the record.
   */
  @POST
  @TransactionPolicy(value = TransactionControl.EXPLICIT)
  @Path("v2/contexts/{context}/workspaces/{id}/execute")
  public void execute(HttpServiceRequest request, HttpServiceResponder responder,
                      @PathParam("context") String namespace,
                      @PathParam("id") String workspaceId) {
    respond(responder, namespace, ns -> {
      validateNamespace(ns, "Executing directives in system namespace is currently not supported");

      DirectiveExecutionRequest directiveExecutionRequest =
          GSON.fromJson(StandardCharsets.UTF_8.decode(request.getContent()).toString(),
              DirectiveExecutionRequest.class);
      Boolean useResult = true; //will be true or false depending on flag for null handling
      if (directiveExecutionRequest.getColumnMappings() != null) {
        useResult = true;
      }

      WorkspaceDetail detail = wsStore.getWorkspaceDetail(new WorkspaceId(ns, workspaceId));
      SampleSpec spec = detail.getWorkspace().getSampleSpec();

      List<Row> result = new ArrayList<>();
      if (useResult) {
//        List<String> columnNames = directiveExecutionRequest.getChangeNullabilityRequest().getColumnNames();
//        UserDefinedAction userDefinedAction =
//            directiveExecutionRequest.getChangeNullabilityRequest().getUserDefinedAction();
        HashMap<String, UserDefinedAction> columnMappings = directiveExecutionRequest.
            getColumnMappings() == null ?
            new HashMap<>() : directiveExecutionRequest.getColumnMappings();
        int minu = 0;

        if (directiveExecutionRequest.getColumnMappings() == null) {
          columnMappings.put("name", UserDefinedAction.NO_ACTION);
          columnMappings.put("city", UserDefinedAction.NO_ACTION);
          if (minu == 1) {
//            columnMappings.put("latitude", UserDefinedAction.ERROR_PIPELINE);
          }
        }
        //part 1 change nullability in schema field
        changeNullability(columnMappings, ns, workspaceId);

        //part 2 go through all the rows to check for null in non-nullable columns
        result = nullChecker(detail.getSample(), columnMappings);
      }

      DirectiveExecutionResponse response = execute(ns, request, new WorkspaceId(ns, workspaceId),
          null, result , useResult);
      responder.sendJson(response);
    });
  }

//  /**
//   * To be called in case a column's nullability is changed also to be called .
//   */
//  @POST
//  @TransactionPolicy(value = TransactionControl.EXPLICIT)
//  @Path("v2/contexts/{context}/workspaces/{id}/initializeNullability")
//  public void initializeNullability(HttpServiceRequest request, HttpServiceResponder responder,
//      @PathParam("context") String namespace,
//      @PathParam("id") String workspaceId) {
//    respond(responder, namespace, ns -> {
//      validateNamespace(ns, "Initializing column Nullability in system namespace is currently
//      not supported");
//      //part 1 initialize the nullability map.
//      WorkspaceDetail detail = wsStore.getWorkspaceDetail(new WorkspaceId(ns, workspaceId));
//      Schema currentSchema = TRANSIENT_STORE.get(TransientStoreKeys.OUTPUT_SCHEMA) != null ?
//          TRANSIENT_STORE.get(TransientStoreKeys.OUTPUT_SCHEMA)
//          : TRANSIENT_STORE.get(TransientStoreKeys.INPUT_SCHEMA);
//      List<Row> result = null;
//      if (currentSchema.getNullabilityMap().isEmpty()) {
//        //initialize the nullability hash map
//        HashMap<String, UserDefinedAction> nullability = new HashMap<>();
//        // assuming all rows have the same columns
//        Row samplerow = detail.getSample().get(0);
//        for (int i=0; i < samplerow.length(); i++){
//          nullability.put(samplerow.getColumn(i), UserDefinedAction.NULLABLE);
//        }
//        currentSchema.setNullabilityMap(nullability);
//        TRANSIENT_STORE.set(TransientVariableScope.GLOBAL,
//        TransientStoreKeys.INPUT_SCHEMA, currentSchema);
//        //part 2 go through all the rows to check for null in non nullable columns
//        result = nullChecker(detail.getSample(),nullability);
//      }
//      ChangeNullabilityResponse response = generateChangeNullabilityResponse(result, 1000);
//      responder.sendJson(response);
//    });
//  }

  public List<Row> nullChecker(List<Row> sample, HashMap<String, UserDefinedAction> columnMappings)
      throws ErrorRowException, DirectiveExecutionException {
    List<Row> result = new ArrayList<>();

    for (Row row : sample) {
      int elsecounts = 0;
      boolean nullPresentinRow = false;

      for (Map.Entry<String, UserDefinedAction> stringUserDefinedActionEntry : columnMappings.entrySet()) {
        HashMap.Entry mapElement
            = (HashMap.Entry) stringUserDefinedActionEntry;
        String columnName = (String) mapElement.getKey();
        UserDefinedAction userDefinedAction = (UserDefinedAction) mapElement.getValue();
        Object value = row.getValue(columnName);
        if (value == null) {
          nullPresentinRow = true;
          switch (userDefinedAction) {
            case SKIP_ROW:
              break;
            case NULLABLE:
              elsecounts++;
              break;
            case NO_ACTION:
              elsecounts++;
              break;
            case ERROR_PIPELINE:
              throw new DirectiveExecutionException(
                  String.format("found null value in non nullable column %s", columnName));
            case SEND_TO_ERROR_COLLECTOR:
              break;
          }
        } else {
          elsecounts++;
        }
      }
      if (nullPresentinRow) {
        if (elsecounts == columnMappings.size()) {
          result.add(row);
        }
      } else {
        result.add(row);
      }
    }
    return result;
  }

//  protected ChangeNullabilityResponse generateChangeNullabilityResponse(
//      List<Row> rows, int limit) throws Exception {
//    List<Map<String, Object>> values = new ArrayList<>(rows.size());
//    Map<String, String> types = new LinkedHashMap<>();
////    SchemaConverter convertor = new SchemaConverter();
//
//    //schema management logic to be added
//
//    // Iterate through all the new rows.
//    for (Row row : rows) {
//      // If output array has more than return result values, we terminate.
//      if (values.size() >= limit) {
//        break;
//      }
//
//      Map<String, Object> value = new HashMap<>(row.width());
//
//      // Iterate through all the fields of the row.
//      for (Pair<String, Object> field : row.getFields()) {
//        String fieldName = field.getFirst();
//        Object object = field.getSecond();
//
//        if (object != null) {
//          //schema management logic to be added
//          if ((object instanceof Iterable)
//              || (object instanceof Row)) {
//            value.put(fieldName, GSON.toJson(object));
//          } else {
//            if ((object.getClass().getMethod("toString").getDeclaringClass() != Object.class)) {
//              value.put(fieldName, object.toString());
//            } else {
//              value.put(fieldName, WranglerDisplaySerializer.NONDISPLAYABLE_STRING);
//            }
//          }
//        } else {
//          value.put(fieldName, null);
//        }
//      }
//      values.add(value);
//    }
//    ChangeNullabilityResponse response =
//        new ChangeNullabilityResponse(values, types.keySet(), types, getWorkspaceSummary(rows));
//    return response;
//  }

//  @POST
//  @TransactionPolicy(value = TransactionControl.EXPLICIT)
//  @Path("v2/contexts/{context}/workspaces/{id}/changeNullability")
//  public void changeNullability(HttpServiceRequest request, HttpServiceResponder responder,
//      @PathParam("context") String namespace,
//      @PathParam("id") String workspaceId) {
//    respond(responder, namespace, ns -> {
//      validateNamespace(ns, "Initializing column Nullability in system namespace is currently not supported");
//      WorkspaceDetail detail = wsStore.getWorkspaceDetail(new WorkspaceId(ns, workspaceId));
//      Schema currentSchema = TRANSIENT_STORE.get(TransientStoreKeys.OUTPUT_SCHEMA) != null ?
//          TRANSIENT_STORE.get(TransientStoreKeys.OUTPUT_SCHEMA) :
//          TRANSIENT_STORE.get(TransientStoreKeys.INPUT_SCHEMA);
//      if (schemaManagementEnabled && currentSchema == null) {
//        SampleSpec spec = detail.getWorkspace().getSampleSpec();
//        // Workaround for uploaded files that don't have the spec set
//        currentSchema = spec != null ? spec.getRelatedPlugins().iterator().next().getSchema() :
//            Schema.recordOf("inputSchema", Schema.Field.of("body", Schema.of(Schema.Type.STRING)));
//        TRANSIENT_STORE.reset(TransientVariableScope.GLOBAL);
//        TRANSIENT_STORE.set(TransientVariableScope.GLOBAL,
//        TransientStoreKeys.INPUT_SCHEMA, currentSchema); //MINU::set
//      }
////      Schema currentSchema = TRANSIENT_STORE.get(TransientStoreKeys.OUTPUT_SCHEMA) != null ?
////          TRANSIENT_STORE.get(TransientStoreKeys.OUTPUT_SCHEMA) :
////          TRANSIENT_STORE.get(TransientStoreKeys.INPUT_SCHEMA);
//      ChangeNullabilityRequest changeNullabilityRequest =
//          GSON.fromJson(StandardCharsets.UTF_8.decode(request.getContent()).toString(),
//              ChangeNullabilityRequest.class);
//      String columnName = changeNullabilityRequest.getColumnName();
//      UserDefinedAction userDefinedAction1 = changeNullabilityRequest.getUserDefinedAction();
//      UserDefinedAction userDefinedAction = UserDefinedAction.NO_ACTION;
//      if (userDefinedAction1.equals("NO_ACTION")) {
//       userDefinedAction = UserDefinedAction.NO_ACTION;
//      } else if (userDefinedAction1.equals("SKIP_ROW")) {
//        userDefinedAction = UserDefinedAction.SKIP_ROW;
//      }
//      List<Row> result = new ArrayList<>();
//      //part 1 change nullability in schema field
//      changeNullability(columnName, userDefinedAction,);
//
//      //part 2 go through all the rows to check for null in non-nullable columns
//      result = nullChecker(detail.getSample(), userDefinedAction, columnName);
//      ChangeNullabilityResponse response = generateChangeNullabilityResponse(result, 1000);
//
//      DirectiveExecutionRequest directiveExecutionRequest =
//          GSON.fromJson(StandardCharsets.UTF_8.decode(request.getContent()).toString(),
//              DirectiveExecutionRequest.class);
//      if (directiveExecutionRequest.getDirectives().isEmpty()) {
//              responder.sendJson(response);
//      }
//      execute(request, responder, namespace, workspaceId);
//    });
//
//  }

  /**
   * Retrieve the directives available in the namespace
   */
  @GET
  @TransactionPolicy(value = TransactionControl.EXPLICIT)
  @Path("v2/contexts/{context}/directives")
  public void getDirectives(HttpServiceRequest request, HttpServiceResponder responder,
                            @PathParam("context") String namespace) {
    respond(responder, namespace, ns -> {
      // CDAP-15397 - reload must be called before it can be safely used
      composite.reload(namespace);
      List<DirectiveUsage> directives = new ArrayList<>();
      for (DirectiveInfo directive : composite.list(namespace)) {
        DirectiveUsage directiveUsage = new DirectiveUsage(directive.name(), directive.usage(),
                                                           directive.description(), directive.scope().name(),
                                                           directive.definition(), directive.categories());
        directives.add(directiveUsage);
      }
      responder.sendJson(new ServiceResponse<>(directives));
    });
  }

  /**
   * Get the specification for the workspace
   */
  @GET
  @TransactionPolicy(value = TransactionControl.EXPLICIT)
  @Path("v2/contexts/{context}/workspaces/{id}/specification")
  public void specification(HttpServiceRequest request, HttpServiceResponder responder,
                            @PathParam("context") String namespace,
                            @PathParam("id") String workspaceId) {
    respond(responder, namespace, ns -> {
      if (ns.getName().equalsIgnoreCase(NamespaceId.SYSTEM.getNamespace())) {
        throw new BadRequestException("Getting specification in system namespace is currently not supported");
      }
      // reload to retrieve the wrangler transform
      composite.reload(namespace);

      WorkspaceId wsId = new WorkspaceId(ns, workspaceId);
      WorkspaceDetail detail = wsStore.getWorkspaceDetail(wsId);
      List<String> directives = new ArrayList<>(detail.getWorkspace().getDirectives());
      UserDirectivesCollector userDirectivesCollector = new UserDirectivesCollector();
//      List<Row> result = executeDirectives(ns.getName(), directives, detail, userDirectivesCollector, null, false);
      userDirectivesCollector.addLoadDirectivesPragma(directives);

      Schema outputSchema;
      if (schemaManagementEnabled) {
        outputSchema = TRANSIENT_STORE.get(TransientStoreKeys.OUTPUT_SCHEMA) != null ?
          TRANSIENT_STORE.get(TransientStoreKeys.OUTPUT_SCHEMA) : TRANSIENT_STORE.get(TransientStoreKeys.INPUT_SCHEMA);
      } else {
        outputSchema = null;
      }
//      else {
//        SchemaConverter schemaConvertor = new SchemaConverter();
//        outputSchema = result.isEmpty() ? null :
//        schemaConvertor.toSchema("record", RowHelper.createMergedRow(result));
//      }
      List<String> nonNullableColumns = new ArrayList<>();
      HashMap<String, UserDefinedAction> columnMappings = new HashMap<>();



      if (!detail.getWorkspace().getColumnMappings().isEmpty()) {
        columnMappings = new HashMap<>(detail.getWorkspace().getColumnMappings());
      } else {
        columnMappings = null;
      }
//      columnMappings.put("iata", UserDefinedAction.NO_ACTION);
//      columnMappings.put("name", UserDefinedAction.SKIP_ROW);

//      UserDefinedAction userDefinedAction = detail.getWorkspace().getUserDefinedAction();
      String columnMappingsString = columnMappings != null ? columnMappings.toString() : "";
      columnMappingsString = columnMappingsString.substring(1, columnMappingsString.length() - 1);

      // check if the rows are empty before going to create a record schema, it will result in a 400 if empty fields
      // are passed to a record type schema
      Map<String, String> properties = ImmutableMap.of("directives", String.join("\n", directives),
                                                       "field", "*",
                                                       "precondition", "false",
                                                       "workspaceId", workspaceId,
          "columnMappings" , columnMappingsString
      );

      Set<StageSpec> srcSpecs = getSourceSpecs(detail, directives);

      ArtifactSummary wrangler = composite.getLatestWranglerArtifact();
      responder.sendString(GSON.toJson(new WorkspaceSpec(
        srcSpecs, new StageSpec(outputSchema, new Plugin("Wrangler", "transform", properties,
                             wrangler == null ? null :
                               new Artifact(wrangler.getName(), wrangler.getVersion(),
                                            wrangler.getScope().name().toLowerCase()))))));
    });
  }

  @POST
  @TransactionPolicy(value = TransactionControl.EXPLICIT)
  @Path("v2/contexts/{context}/workspaces/{id}/applyRecipe/{recipe-id}")
  public void applyRecipe(HttpServiceRequest request, HttpServiceResponder responder,
                          @PathParam("context") String namespace,
                          @PathParam("id") String workspaceId,
                          @PathParam("recipe-id") String recipeIdString) {
    respond(responder, namespace, ns -> {
      validateNamespace(ns, "Executing directives in system namespace is currently not supported");

      RecipeId recipeId = RecipeId.builder(ns).setRecipeId(recipeIdString).build();
      Recipe recipe = recipeStore.getRecipeById(recipeId);

      DirectiveExecutionResponse response = execute(ns, request, new WorkspaceId(ns, workspaceId),
                                                    recipe.getDirectives(), null, false);
      responder.sendJson(response);
    });
  }

  private void validateNamespace(NamespaceSummary ns, String errorMessage) {
    if (ns.getName().equalsIgnoreCase(NamespaceId.SYSTEM.getNamespace())) {
      throw new BadRequestException(errorMessage);
    }
  }

  private DirectiveExecutionResponse execute(NamespaceSummary ns, HttpServiceRequest request,
                                             WorkspaceId workspaceId,
                                             List<String> recipeDirectives,
      List<Row> inputRows, Boolean useInputRows) throws Exception {
    DirectiveExecutionRequest executionRequest =
      GSON.fromJson(StandardCharsets.UTF_8.decode(request.getContent()).toString(),
                    DirectiveExecutionRequest.class);

    List<String> directives = new ArrayList<>(executionRequest.getDirectives());
    if (recipeDirectives != null) {
      directives.addAll(recipeDirectives);
    }

    WorkspaceDetail detail = wsStore.getWorkspaceDetail(workspaceId);
    UserDirectivesCollector userDirectivesCollector = new UserDirectivesCollector();

    List<Row> result = executeDirectives(ns.getName(), directives, detail,
                                         userDirectivesCollector, inputRows, useInputRows);
    DirectiveExecutionResponse response = generateExecutionResponse(result,
                                                                    executionRequest.getLimit());
    userDirectivesCollector.addLoadDirectivesPragma(directives);
    Workspace newWorkspace = Workspace.builder(detail.getWorkspace())
      .setDirectives(directives)
      .setUpdatedTimeMillis(System.currentTimeMillis()).build();
    wsStore.updateWorkspace(workspaceId, newWorkspace);
    return response;
  }

  private void changeNullability(HashMap<String, UserDefinedAction> columnMappings,
      NamespaceSummary ns, String workspaceId) throws Exception {
    try {
      WorkspaceDetail detail = wsStore.getWorkspaceDetail(new WorkspaceId(ns, workspaceId));
      Workspace workspace = wsStore.getWorkspace(new WorkspaceId(ns, workspaceId));
// Create a Set and pass List object as parameter
//      HashSet<String> nonNullableColumns = new HashSet<String>(columnNames);
//      nonNullableColumns.addAll(columnNames);
//      workspace.setUserDefinedAction(userDefinedAction);
//      workspace.setNonNullableColumns(new HashSet<String>(columnNames));
      workspace.setColumnMappings(columnMappings);
      wsStore.updateWorkspace(new WorkspaceId(ns, workspaceId), workspace);
    } catch (Exception e) {
      throw new RuntimeException("Error in setting nullability of the column ", e);
    }
    }


  /**
   * Get source specs, contains some hacky way on dealing with the csv parser
   */
  private Set<StageSpec> getSourceSpecs(WorkspaceDetail detail, List<String> directives) {
    SampleSpec sampleSpec = detail.getWorkspace().getSampleSpec();
    Set<StageSpec> srcSpecs = sampleSpec == null ? Collections.emptySet() : sampleSpec.getRelatedPlugins();

    // really hacky way for the parse-as-csv directive, should get removed once we have support to provide the
    // format properties when doing sampling
    boolean shouldCopyHeader =
      directives.stream()
        .map(String::trim)
        .anyMatch(directive -> directive.startsWith("parse-as-csv") && directive.endsWith("true"));

//    List<String> nonNullableColumns = new ArrayList<>();
//    if (detail.getWorkspace().getNonNullableColumns() != null) {
//      nonNullableColumns.addAll(detail.getWorkspace().getNonNullableColumns());
//    } else {
//      nonNullableColumns = null;
//    }
//
//    UserDefinedAction userDefinedAction = detail.getWorkspace().getUserDefinedAction();
//    String nonNullableColumnsString = nonNullableColumns != null ? String.join("\n", nonNullableColumns) : "latitude";



    if (shouldCopyHeader && !srcSpecs.isEmpty()) {
      srcSpecs = srcSpecs.stream().map(stageSpec -> {
        Plugin plugin = stageSpec.getPlugin();
        Map<String, String> srcProperties = new HashMap<>(plugin.getProperties());
        srcProperties.put("copyHeader", "true");
        return new StageSpec(stageSpec.getSchema(), new Plugin(plugin.getName(), plugin.getType(),
                                                               srcProperties, plugin.getArtifact()));
      }).collect(Collectors.toSet());
    }
    return srcSpecs;
  }

  /**
   * Get the workspace name, the generation rule is like:
   * 1. If the path is not null or empty, the name will be last portion of path starting from "/".
   * If "/" does not exist, the name will be the path itself.
   * 2. If path is null or empty or equal to "/", the name will be the uuid for the workspace
   */
  private String generateWorkspaceName(WorkspaceId id, @Nullable String path) {
    if (Strings.isNullOrEmpty(path)) {
      return id.getWorkspaceId();
    }

    // remove trailing "/"
    path = path.replaceAll("/+$", "");

    int last = path.lastIndexOf('/');
    // if found an "/", take the rest as name
    if (last >= 0) {
      return path.substring(last + 1);
    }
    // if not, check if path is empty or not
    return path.isEmpty() ? id.getWorkspaceId() : path;
  }

  /**
   * Executes the given list of directives on the given workspace.
   *
   * @param namespace the namespace to operate on for finding user defined directives
   * @param directives the list of directives to apply. The list provided must be a mutable list for the addition of
   *                   {@code #pragma} directives for loading UDDs.
   * @param detail the workspace to operate on
   * @param grammarVisitor visitor to call while parsing directives
   * @return the resulting rows after applying the directives
   */
  private <E extends Exception> List<Row> executeDirectives(String namespace,
                                                            List<String> directives,
                                                            WorkspaceDetail detail,
                                                            GrammarWalker.Visitor<E> grammarVisitor,
      List<Row> inputRows, Boolean useInputRows) throws Exception {
    // Remove all the #pragma from the existing directives. New ones will be generated.
    directives.removeIf(d -> PRAGMA_PATTERN.matcher(d).find());

    if (schemaManagementEnabled) {
      SampleSpec spec = detail.getWorkspace().getSampleSpec();
      // Workaround for uploaded files that don't have the spec set
      Schema inputSchema = spec != null ? spec.getRelatedPlugins().iterator().next().getSchema() :
        Schema.recordOf("inputSchema", Schema.Field.of("body", Schema.of(Schema.Type.STRING)));
      TRANSIENT_STORE.reset(TransientVariableScope.GLOBAL);
      TRANSIENT_STORE.set(TransientVariableScope.GLOBAL, TransientStoreKeys.INPUT_SCHEMA, inputSchema);
    }

    return getContext().isRemoteTaskEnabled() ?
      executeRemotely(namespace, directives, detail, grammarVisitor) :
      executeLocally(namespace, directives, detail, grammarVisitor, inputRows, useInputRows);
  }

  /**
   * Executes the given list of directives on the given workspace locally in the same JVM.
   *
   * @param namespace the namespace to operate on for finding user defined directives
   * @param directives the list of directives to apply. The list provided must be a mutable list for the addition of
   *                   {@code #pragma} directives for loading UDDs.
   * @param detail the workspace to operate on
   * @param grammarVisitor visitor to call while parsing directives
   * @return the resulting rows after applying the directives
   */
  private <E extends Exception> List<Row> executeLocally(String namespace, List<String> directives,
                                   WorkspaceDetail detail, GrammarWalker.Visitor<E> grammarVisitor,
      List<Row> inputRows, Boolean useInputRows)
      throws DirectiveLoadException, DirectiveParseException, E,
      RecipeException, ErrorRowException, DirectiveExecutionException {

    // load the udd
    composite.reload(namespace);
    if (useInputRows) {
      return executeDirectives(namespace, directives, inputRows,
          grammarVisitor, detail.getWorkspace().getColumnMappings());
    }
    return executeDirectives(namespace, directives, new ArrayList<>(detail.getSample()),
                             grammarVisitor, detail.getWorkspace().getColumnMappings());
  }

  /**
   * Executes the given list of directives on the given workspace remotely using the task worker framework.
   *
   * @param namespace the namespace to operate on for finding user defined directives
   * @param directives the list of directives to apply. The list provided must be a mutable list for the addition of
   *                   {@code #pragma} directives for loading UDDs.
   * @param detail the workspace to operate on
   * @param grammarVisitor visitor to call while parsing directives
   * @return the resulting rows after applying the directives
   */
  private <E extends Exception> List<Row> executeRemotely(String namespace, List<String> directives,
                                    WorkspaceDetail detail, GrammarWalker.Visitor<E> grammarVisitor) throws Exception {

    GrammarMigrator migrator = new MigrateToV2(directives);
    String recipe = migrator.migrate();
    Map<String, DirectiveClass> systemDirectives = new HashMap<>();

    // Gather system directives and call additional visitor.
    GrammarWalker walker = new GrammarWalker(new RecipeCompiler(), new ConfigDirectiveContext(DirectiveConfig.EMPTY));
    AtomicBoolean hasDirectives = new AtomicBoolean();
    walker.walk(recipe, (command, tokenGroup) -> {
      DirectiveInfo info = SystemDirectiveRegistry.INSTANCE.get(command);
      if (info != null) {
        systemDirectives.put(command, info.getDirectiveClass());
      }
      grammarVisitor.visit(command, tokenGroup);
      hasDirectives.set(true);
    });

    // If no directives to execute, just return
    if (!hasDirectives.get()) {
      return detail.getSample();
    }

    RemoteDirectiveRequest directiveRequest = new RemoteDirectiveRequest(recipe, systemDirectives,
                                                                         namespace, detail.getSampleAsBytes());
    RunnableTaskRequest runnableTaskRequest = RunnableTaskRequest.getBuilder(RemoteExecutionTask.class.getName())
      .withParam(GSON.toJson(directiveRequest))
      .build();
    byte[] bytes = getContext().runTask(runnableTaskRequest);
    return new ObjectSerDe<List<Row>>().toObject(bytes);
  }

  private List<Row> getSample(SampleResponse sampleResponse) {
    List<Row> rows = new ArrayList<>();
    if (!sampleResponse.getSample().isEmpty()) {
      for (StructuredRecord record : sampleResponse.getSample()) {
        rows.add(StructuredToRowTransformer.transform(record));
      }
    }
    return rows;
  }
}
