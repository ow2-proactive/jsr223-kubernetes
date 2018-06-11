/*
 * ProActive Parallel Suite(TM):
 * The Open Source library for parallel and distributed
 * Workflows & Scheduling, Orchestration, Cloud Automation
 * and Big Data Analysis on Enterprise Grids & Clouds.
 *
 * Copyright (c) 2007 - 2017 ActiveEon
 * Contact: contact@activeeon.com
 *
 * This library is free software: you can redistribute it and/or
 * modify it under the terms of the GNU Affero General Public License
 * as published by the Free Software Foundation: version 3 of
 * the License.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 *
 * If needed, contact us to obtain a release under GPL Version 2 or 3
 * or a different license than the AGPL.
 */
package jsr223.kubernetes;

import java.io.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import javax.script.AbstractScriptEngine;
import javax.script.Bindings;
import javax.script.ScriptContext;
import javax.script.ScriptEngineFactory;
import javax.script.ScriptException;
import javax.script.SimpleBindings;

import org.apache.log4j.Logger;
import org.ow2.proactive.scheduler.common.SchedulerConstants;

import com.google.gson.JsonElement;
import com.google.gson.JsonStreamParser;

import jsr223.kubernetes.entrypoint.EntryPoint;
import jsr223.kubernetes.model.KubernetesResource;
import jsr223.kubernetes.processbuilder.KubernetesProcessBuilderUtilities;
import jsr223.kubernetes.processbuilder.SingletonKubernetesProcessBuilderFactory;
import jsr223.kubernetes.utils.*;
import lombok.NoArgsConstructor;


/**
 * @author ActiveEon Team
 * @since 17/05/2018
 */
@NoArgsConstructor
public class KubernetesScriptEngine extends AbstractScriptEngine {

    private static final Logger log = Logger.getLogger(KubernetesScriptEngine.class);

    // K8S manifest
    public static final String K8S_MANIFEST_FILE_NAME = "k8s-manifest.yml";

    private File k8sManifestFile = null;

    // Utils
    private GenericFileWriter k8SManifestFileWriter = new GenericFileWriter();

    private KubernetesCommandCreator kubernetesCommandCreator = new KubernetesCommandCreator();

    private KubernetesProcessBuilderUtilities processBuilderUtilities = new KubernetesProcessBuilderUtilities();

    // Bindings & Generic Info
    private Map<String, String> genericInfo;

    Bindings bindingsShared;

    // Optional parameters passed within generic info to customize the script engine behavior
    private boolean k8sCreateOnly = false, k8sDeleteOnly = false;

    private String k8sResourceToStream = null;

    // List of the k8s resources created by the current tasks
    private ArrayList<KubernetesResource> k8sResourcesList = new ArrayList<KubernetesResource>();

    @Override
    public Object eval(String k8s_manifest, ScriptContext context) throws ScriptException {

        // Step 0: Populate the bindings and the Generic Info
        populateBindingAndGI();

        // Write the manifest file
        writeManifestFile(k8s_manifest);

        // Mode 1: Only create the k8s resources
        if (k8sCreateOnly) {
            createKubernetesResources();
        }

        // Mode 2: Create, stream logs and delete the k8s resources
        else if (!k8sCreateOnly && !k8sDeleteOnly) {
            createKubernetesResources();
            switch (k8sResourcesList.size()) {
                case 0:
                    throw new ScriptException("No k8s resources were created; cannot stream logs.");
                case 1:
                    kubecltLog(k8sResourcesList.get(0).getKind(),
                               k8sResourcesList.get(0).getName(),
                               k8sResourcesList.get(0).getNamespace());
                    break;
                default:
                    // more than one k8s resources has been created, and we can only stream logs for one
                    KubernetesResource chosen = chooseResourceToStream();
                    kubecltLog(chosen.getKind(), chosen.getName(), chosen.getNamespace());
                    break;
            }
            cleanKubernetesResources();
        }

        // Mode 3: only delete the k8s resources
        else if (k8sDeleteOnly) {
            cleanKubernetesResources();
        }

        // Delete manifest file
        deleteManifestFile();

        // Step 4: exit
        Object resultValue = true;
        return resultValue;
    }

    private void populateBindingAndGI() throws ScriptException {

        EntryPoint entryPoint = new EntryPoint();
        bindingsShared = entryPoint.getBindings();
        bindingsShared.putAll(context.getBindings(ScriptContext.ENGINE_SCOPE));
        if (bindingsShared == null) {
            throw new ScriptException("No bindings specified in the script context");
        }
        context.getBindings(ScriptContext.ENGINE_SCOPE).putAll(bindingsShared);

        // Retrieving Generic Info
        genericInfo = (Map<String, String>) context.getBindings(ScriptContext.ENGINE_SCOPE)
                                                   .get(SchedulerConstants.GENERIC_INFO_BINDING_NAME);

        // Parsing the optional parameters of the script engine provided as generic info
        if (genericInfo != null) {
            if (genericInfo.containsKey("K8S_CREATE_ONLY")) {
                k8sCreateOnly = Boolean.valueOf(genericInfo.get("K8S_CREATE_ONLY"));
            }
            if (genericInfo.containsKey("K8S_DELETE_ONLY")) {
                k8sDeleteOnly = Boolean.valueOf(genericInfo.get("K8S_DELETE_ONLY"));
            }
            if (genericInfo.containsKey("K8S_STREAM_LOGS")) {
                k8sDeleteOnly = Boolean.valueOf(genericInfo.get("K8S_STREAM_LOGS"));
            }
            if (genericInfo.containsKey("K8S_RESOURCE_TO_STREAM")) {
                k8sResourceToStream = genericInfo.get("K8S_RESOURCE_TO_STREAM");
            }
        }
    }

    private void writeManifestFile(String k8s_manifest) {
        // Write k8s manifest to file
        try {
            k8sManifestFile = k8SManifestFileWriter.forceFileToDisk(k8s_manifest, K8S_MANIFEST_FILE_NAME);
        } catch (IOException e) {
            log.warn("Failed to write content to kubernetes manifest file: ", e);
        }
    }

    private void createKubernetesResources() throws ScriptException {
        log.info("Creating Kubernetes resources from manifest.");

        // Prepare kubectl command
        String[] kubectlCommand = kubernetesCommandCreator.createKubectlCreateCommand(K8S_MANIFEST_FILE_NAME);

        String kubectl_output = null;

        //Create a process builder
        Process process = null;
        ProcessBuilder processBuilder = SingletonKubernetesProcessBuilderFactory.getInstance()
                                                                                .getProcessBuilder(kubectlCommand);

        try {
            //Run the 'kubectl create' process
            process = processBuilder.start();
            int exitValue = process.waitFor();
            // Retrieve the process stdout
            try (BufferedReader buffer = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                // Retrieve the 'kubectl create' JSON output
                kubectl_output = buffer.lines().collect(Collectors.joining(" "));
            }
            if (exitValue != 0) {
                // An error occured during k8s resource(s) creation.
                log.error("Could not create the K8S resources successfully.");
                log.error(kubectl_output);
                cleanKubernetesResources();
                deleteManifestFile();
                throw new ScriptException("Kubernetes resources creation has failed with exit code " + exitValue);
            }
            // Creation was successful, going to parse the json output of 'kubectl' to keep track of the newly created resources
            JsonStreamParser parser = new JsonStreamParser(kubectl_output);
            // Retrieve created resource(s) info (kind, resource name & namespace)
            Consumer<JsonElement> k8sResourceJsonParse = (JsonElement k8sResource) -> parseK8sResourceJson(k8sResource);
            parser.forEachRemaining(k8sResourceJsonParse);
        } catch (IOException e) {
            cleanKubernetesResources();
            deleteManifestFile();
            throw new ScriptException("I/O error when trying to create kubernetes resources. Exiting.\nException: " + e);
        } catch (InterruptedException e1) {
            cleanKubernetesResources();
            deleteManifestFile();
            throw new ScriptException("Interrupted when trying to create kubernetes resources. Exiting.\nException: " +
                                      e1);
        }
    }

    private void parseK8sResourceJson(JsonElement resource_json) {
        String kind = resource_json.getAsJsonObject().get("kind").getAsString().toLowerCase();
        String name = resource_json.getAsJsonObject().get("metadata").getAsJsonObject().get("name").getAsString();
        String namespace = resource_json.getAsJsonObject()
                                        .get("metadata")
                                        .getAsJsonObject()
                                        .get("namespace")
                                        .getAsString();

        log.info("Successfully created K8S resource: " + kind + '/' + name + " in namespace " + namespace + ".");
        k8sResourcesList.add(new KubernetesResource(kind, name, namespace));
    }

    private KubernetesResource chooseResourceToStream() {
        // first choice: the user has specified a resource to stream within the generic info of the task
        if (k8sResourceToStream != null) {
            log.info("User has specified a resource to stream, will stream this one: " + k8sResourceToStream);
            String[] resource_info = k8sResourceToStream.split("/"); // syntax is namespace/kind/name
            return new KubernetesResource(resource_info[1], resource_info[2], resource_info[0]);
        } else {
            // get the list of log-streamable resources
            List<KubernetesResource> streamableResources = k8sResourcesList.stream()
                                                                           .filter(r -> r.isLogStreamable())
                                                                           .collect(Collectors.toList());
            log.info("Found " + streamableResources.size() + " log-streamable resources.");
            // We assume at least one log streamble resource has been created
            if (streamableResources.size() > 1) {
                log.info("User did not specify resource to stream and more than 1 resources is streamable; selecting first one: " +
                         streamableResources.get(0).getKind() + "/" + streamableResources.get(0).getName());
                return streamableResources.get(0);
            } else {
                log.info("User did not specify resource to stream but 1 and only 1 resource is streamable; selecting this one: " +
                         streamableResources.get(0).getKind() + "/" + streamableResources.get(0).getName());
                return streamableResources.get(0);
            }
        }
    }

    private void kubecltLog(String k8sResourceKind, String k8sResourceName, String k8sResourceNamespace) {
        log.debug("Kubectl logs thread started.");

        while (true) { // In case of early call to logs (e.g. during ContainerCreating state)

            try {

                String[] kubectlCommand = kubernetesCommandCreator.createKubectlLogsCommand(k8sResourceKind,
                                                                                            k8sResourceName,
                                                                                            k8sResourceNamespace);

                // Override current process builder
                ProcessBuilder processBuilder = SingletonKubernetesProcessBuilderFactory.getInstance()
                                                                                        .getProcessBuilder(kubectlCommand);

                Process process = processBuilder.start();

                //Wait for the process to exit
                process.waitFor();

                if (process.exitValue() == 0) {
                    log.info(" ");
                    log.info("[Output from kubernetes resource " + k8sResourceKind + '/' + k8sResourceName + ": ]");
                    processBuilderUtilities.attachStreamsToProcess(process,
                                                                   context.getWriter(),
                                                                   context.getErrorWriter(),
                                                                   null);
                    log.info("[End of output]");
                    log.info("");
                    break;
                } else {
                    Thread.sleep(1000); // wait for the kubernetes resource to be in appropriate state for log streaming
                }

            } catch (InterruptedException e) { // TODO: define own exception KubernetesJobCompletedException
                log.warn("Interrupted when trying to stream kubernetes resources logs. Stopping log streaming.\nException: " +
                         e);
                cleanKubernetesResources();
                deleteManifestFile();
                return;
            } catch (IOException e) {
                log.warn("I/O error when trying to stream kubernetes resources logs. Stopping log streaming.\nException: " +
                         e);
            }
        }

    }

    private String cleanKubernetesResources() {
        try {
            Process k8s_delete_process = SingletonKubernetesProcessBuilderFactory.getInstance()
                                                                                 .getProcessBuilder(kubernetesCommandCreator.createKubectlDeleteCommand(K8S_MANIFEST_FILE_NAME))
                                                                                 .start();
            k8s_delete_process.waitFor();
            try (BufferedReader buffer = new BufferedReader(new InputStreamReader(k8s_delete_process.getInputStream()))) {
                String deleted_resource = buffer.lines().collect(Collectors.joining(" "));
                log.info("Successfully deleted K8S resource: " + deleted_resource);
                return deleted_resource;
            }
        } catch (InterruptedException e) {
            log.warn("Interrupted when trying to delete/clean kubernetes resources. Exiting.\nException: " + e);
        } catch (IOException e) {
            log.warn("I/O error when trying to delete/clean kubernetes resources. Exiting.\nException: " + e);
        }

        return null;
    }

    private void deleteManifestFile() {
        if (k8sManifestFile != null) {
            boolean deleted = k8sManifestFile.delete();
            if (!deleted) {
                log.warn("File: " + k8sManifestFile.getAbsolutePath() + " was not deleted.");
            } else {
                k8sManifestFile = null;
            }
        }
    }

    @Override
    public Object eval(Reader reader, ScriptContext context) throws ScriptException {

        StringWriter stringWriter = new StringWriter();

        try {
            KubernetesProcessBuilderUtilities.pipe(reader, stringWriter);
        } catch (IOException e) {
            log.warn("Failed to convert Reader into StringWriter. Not possible to execute Kubernetes task.");
            log.debug("Failed to convert Reader into StringWriter. Not possible to execute Kubernetes task.", e);
        }

        return eval(stringWriter.toString(), context);
    }

    @Override
    public Bindings createBindings() {
        return new SimpleBindings();
    }

    @Override
    public ScriptEngineFactory getFactory() {
        return new KubernetesScriptEngineFactory();
    }
}
