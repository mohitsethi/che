/*
 * Copyright (c) 2012-2018 Red Hat, Inc.
 * This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License 2.0
 * which is available at https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 *   Red Hat, Inc. - initial API and implementation
 */
package org.eclipse.che.api.devfile.server;

import static java.util.Collections.emptyMap;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import javax.inject.Inject;
import javax.inject.Singleton;
import org.eclipse.che.api.core.ConflictException;
import org.eclipse.che.api.core.NotFoundException;
import org.eclipse.che.api.core.ServerException;
import org.eclipse.che.api.core.ValidationException;
import org.eclipse.che.api.devfile.model.Devfile;
import org.eclipse.che.api.devfile.server.validator.DevfileIntegrityValidator;
import org.eclipse.che.api.devfile.server.validator.DevfileSchemaValidator;
import org.eclipse.che.api.workspace.server.WorkspaceManager;
import org.eclipse.che.api.workspace.server.model.impl.WorkspaceConfigImpl;
import org.eclipse.che.api.workspace.server.model.impl.WorkspaceImpl;
import org.eclipse.che.commons.env.EnvironmentContext;

@Singleton
public class DevfileManager {

  private final ObjectMapper objectMapper;
  private DevfileSchemaValidator schemaValidator;
  private DevfileIntegrityValidator integrityValidator;
  private DevfileConverter devfileConverter;
  private WorkspaceManager workspaceManager;

  @Inject
  public DevfileManager(
      DevfileSchemaValidator schemaValidator,
      DevfileIntegrityValidator integrityValidator,
      DevfileConverter devfileConverter,
      WorkspaceManager workspaceManager) {
    this.schemaValidator = schemaValidator;
    this.integrityValidator = integrityValidator;
    this.devfileConverter = devfileConverter;
    this.workspaceManager = workspaceManager;
    this.objectMapper = new ObjectMapper(new YAMLFactory());
  }

  /**
   * Creates {@link WorkspaceConfigImpl} from given devfile content. Performs schema and integrity
   * validation of input data before conversion.
   *
   * @param devfileContent raw content of devfile
   * @param verbose when true, method returns more explained validation error messages if any
   * @return WorkspaceConfig created from the devfile
   * @throws DevfileFormatException when any of schema or integrity validations fail
   * @throws JsonProcessingException when parsing error occurs
   */
  public WorkspaceConfigImpl convert(String devfileContent, boolean verbose)
      throws DevfileFormatException, JsonProcessingException {
    JsonNode parsed = schemaValidator.validateBySchema(devfileContent, verbose);
    Devfile devFile = objectMapper.treeToValue(parsed, Devfile.class);
    integrityValidator.validateDevfile(devFile);
    return devfileConverter.devFileToWorkspaceConfig(devFile);
  }

  /**
   * Creates workspace from given config
   *
   * @param workspaceConfig initial workspace configuration
   * @return created workspace instance
   * @throws ServerException
   * @throws ConflictException
   * @throws NotFoundException
   * @throws ValidationException
   */
  public WorkspaceImpl createWorkspace(WorkspaceConfigImpl workspaceConfig)
      throws ServerException, ConflictException, NotFoundException, ValidationException {
    final String namespace = EnvironmentContext.getCurrent().getSubject().getUserName();
    return workspaceManager.createWorkspace(
        findAvailableName(workspaceConfig), namespace, emptyMap());
  }

  public Devfile exportWorkspace(String key)
      throws NotFoundException, ServerException, ConflictException {
    WorkspaceImpl workspace = workspaceManager.getWorkspace(key);
    try {
      return devfileConverter.workspaceToDevFile(workspace.getConfig());
    } catch (WorkspaceExportException e) {
      throw new ConflictException(e.getMessage());
    }
  }

  private WorkspaceConfigImpl findAvailableName(WorkspaceConfigImpl config) throws ServerException {
    String nameCandidate = config.getName();
    String namespace = EnvironmentContext.getCurrent().getSubject().getUserName();
    int counter = 0;
    while (true) {
      try {
        workspaceManager.getWorkspace(nameCandidate, namespace);
        nameCandidate = config.getName() + "_" + ++counter;
      } catch (NotFoundException nf) {
        config.setName(nameCandidate);
        break;
      }
    }
    return config;
  }
}
