/**
 * Copyright (C) 2016 Red Hat, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.fabric8.jenkins.openshiftsync;

import hudson.Extension;
import hudson.model.Item;
import hudson.model.listeners.ItemListener;
import io.fabric8.kubernetes.client.KubernetesClientException;
import io.fabric8.openshift.api.model.BuildConfig;
import io.fabric8.openshift.api.model.BuildConfigBuilder;
import org.apache.commons.lang.StringUtils;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.kohsuke.stapler.DataBoundConstructor;

import java.util.logging.Level;
import java.util.logging.Logger;

import static io.fabric8.jenkins.openshiftsync.BuildConfigToJobMap.removeJobWithBuildConfig;
import static io.fabric8.jenkins.openshiftsync.BuildConfigToJobMapper.updateBuildConfigFromJob;
import static io.fabric8.jenkins.openshiftsync.OpenShiftUtils.getOpenShiftClient;
import static java.net.HttpURLConnection.HTTP_NOT_FOUND;

/**
 * Listens to {@link WorkflowJob} objects being updated via the web console or Jenkins REST API and replicating
 * the changes back to the OpenShift {@link BuildConfig} for the case where folks edit inline Jenkinsfile flows
 * inside the Jenkins UI
 */
@Extension
public class PipelineJobListener extends ItemListener {
  private static final Logger logger = Logger.getLogger(PipelineJobListener.class.getName());

  private String server;
  private String namespace;
  private String jobNamePattern;

  public PipelineJobListener() {
    init();
  }

  @DataBoundConstructor
  public PipelineJobListener(String server, String namespace, String jobNamePattern) {
    this.server = server;
    this.namespace = namespace;
    this.jobNamePattern = jobNamePattern;
    init();
  }

  @Override
  public String toString() {
    return "PipelineJobListener{" +
            "server='" + server + '\'' +
            ", namespace='" + namespace + '\'' +
            ", jobNamePattern='" + jobNamePattern + '\'' +
            '}';
  }

  private void init() {
    namespace = OpenShiftUtils.getNamespaceOrUseDefault(namespace, getOpenShiftClient());
  }

  @Override
  public void onCreated(Item item) {
    reconfigure();
    super.onCreated(item);
    upsertItem(item);
  }

  @Override
  public void onUpdated(Item item) {
    reconfigure();
    super.onUpdated(item);
    upsertItem(item);
  }

  @Override
  public void onDeleted(Item item) {
    reconfigure();
    super.onDeleted(item);
    if (item instanceof WorkflowJob) {
      WorkflowJob job = (WorkflowJob) item;
      BuildConfigProjectProperty property = buildConfigProjectForJob(job);
      if (property != null) {
        logger.info("Deleting BuildConfig " + property.getNamespace() + "/" + property.getName());

        String namespace = property.getNamespace();
        String buildConfigName = property.getName();
        BuildConfig buildConfig = getOpenShiftClient().buildConfigs().inNamespace(namespace).withName(buildConfigName).get();
        if (buildConfig != null) {
          try {
            getOpenShiftClient().buildConfigs().inNamespace(namespace).withName(buildConfigName).delete();
          } catch (KubernetesClientException e) {
            if (HTTP_NOT_FOUND != e.getCode()) {
              logger.log(Level.WARNING, "Failed to delete BuildConfig in namespace: " + namespace + " for name: " + buildConfigName, e);
            }
          } catch (Exception e) {
            logger.log(Level.WARNING, "Failed to delete BuildConfig in namespace: " + namespace + " for name: " + buildConfigName, e);
          } finally {
            removeJobWithBuildConfig(buildConfig);
          }
        }
      }
    }
  }

  public void upsertItem(Item item) {
    if (item instanceof WorkflowJob) {
      WorkflowJob job = (WorkflowJob) item;
      BuildConfigProjectProperty property = buildConfigProjectForJob(job);
      if (property != null) {
        logger.info("Upsert WorkflowJob " + job.getName() + " to BuildConfig: "+ property.getNamespace() + "/" + property.getName() + " in OpenShift");
        upsertBuildConfigForJob(job, property);
      }
    }
  }

  /**
   * Returns the mapping of the jenkins workflow job to a qualified namespace and BuildConfig name
   */
  private BuildConfigProjectProperty buildConfigProjectForJob(WorkflowJob job) {
    BuildConfigProjectProperty property = job.getProperty(BuildConfigProjectProperty.class);
    if (property != null) {
      if (StringUtils.isNotBlank(property.getNamespace()) && StringUtils.isNotBlank(property.getName())) {
        return property;
      }

      // TODO load pattern from configuration!
      String patternRegex = this.jobNamePattern;
      String buildConfigName = job.getName();
      if (StringUtils.isNotEmpty(buildConfigName) && StringUtils.isNotEmpty(patternRegex) && buildConfigName.matches(patternRegex)) {
        if (!StringUtils.isNotEmpty(property.getNamespace())) {
          property.setNamespace(namespace);
        }
        if (!StringUtils.isNotEmpty(property.getName())) {
          // TODO we may have to swizzle the string to ensure its a valid name though
          // e.g. lowercase + no special characters
          property.setName(buildConfigName);
        }
        if (!StringUtils.isNotEmpty(property.getBuildRunPolicy())) {
          property.setBuildRunPolicy("Serial");
        }

        // TODO what to do for these values?
        String resourceVersion = null;
        String uuid = null;

        logger.info("Creating BuildConfigProjectProperty for namespace: " + property.getNamespace() + " name: " + property.getName());
        return property;
      }
    }

    return null;
  }

  private void upsertBuildConfigForJob(WorkflowJob job, BuildConfigProjectProperty buildConfigProjectProperty) {
    boolean create = false;
    BuildConfig jobBuildConfig = buildConfigProjectProperty.getBuildConfig();
    if (jobBuildConfig == null) {
      create = true;
      jobBuildConfig = new BuildConfigBuilder().
              withNewMetadata().withName(buildConfigProjectProperty.getName()).
              withNamespace(buildConfigProjectProperty.getNamespace()).endMetadata().
              withNewSpec().endSpec().build();
    }
    updateBuildConfigFromJob(job, jobBuildConfig);

    if (create) {
      try {
        getOpenShiftClient().buildConfigs().inNamespace(jobBuildConfig.getMetadata().getNamespace()).create(jobBuildConfig);
      } catch (Exception e) {
        logger.log(Level.WARNING, "Failed to create BuildConfig: " + NamespaceName.create(jobBuildConfig) + ". " + e, e);
      }
    } else {
      try {
        getOpenShiftClient().buildConfigs().inNamespace(jobBuildConfig.getMetadata().getNamespace()).withName(jobBuildConfig.getMetadata().getName()).cascading(false).replace(jobBuildConfig);
      } catch (Exception e) {
        logger.log(Level.WARNING, "Failed to update BuildConfig: " + NamespaceName.create(jobBuildConfig) + ". " + e, e);
      }
    }
  }

  /**
   * TODO is there a cleaner way to get this class injected with any new configuration from GlobalPluginConfiguration?
   */
  private void reconfigure() {
    GlobalPluginConfiguration config = GlobalPluginConfiguration.get();
    if (config != null) {
      this.jobNamePattern = config.getJobNamePattern();
      this.namespace = config.getNamespace();
      this.server = config.getServer();
      logger.info("====== Configured from the global configuration: " + this);
    }
  }


}
