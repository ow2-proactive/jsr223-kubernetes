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
import java.util.*;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import javax.script.AbstractScriptEngine;
import javax.script.Bindings;
import javax.script.ScriptContext;
import javax.script.ScriptEngineFactory;
import javax.script.ScriptException;
import javax.script.SimpleBindings;

import org.apache.log4j.Logger;

import com.google.gson.JsonElement;
import com.google.gson.JsonStreamParser;

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

    // K8S manifest file
    public static final String K8S_MANIFEST_FILE_NAME = "k8s-manifest.yml";

    private File k8sManifestFile = null;

    // Kubectl process
    private KubernetesCommandCreator kubernetesCommandCreator = new KubernetesCommandCreator();

    private KubernetesProcessBuilderUtilities processBuilderUtilities = new KubernetesProcessBuilderUtilities();

    // GI, bindings and variables
    private BindingUtils bindings = new BindingUtils();

    // Optional parameters passed within generic info to customize the script engine behavior
    private boolean k8sCreateOnly = false, k8sDeleteOnly = false;

    private String k8sResourceToStream = null;

    // List of the k8s resources created in the current task
    private ArrayList<KubernetesResource> k8sResourcesList = new ArrayList<KubernetesResource>();

    // Constants
    public static final String GI_K8S_CREATE_ONLY = "genericInformation_K8S_CREATE_ONLY";

    public static final String GI_K8S_DELETE_ONLY = "genericInformation_K8S_DELETE_ONLY";

    public static final String GI_K8S_STREAM_LOGS = "genericInformation_K8S_STREAM_LOGS";

    public static final String GI_K8S_RESOURCE_TO_STREAM = "genericInformation_K8S_RESOURCE_TO_STREAM";

    /****************************************/
    /* Kubernetes script engine main method */
    /****************************************/

    @Override
    public Object eval(String k8s_manifest, ScriptContext context) throws ScriptException {

        // Step 0: Populate the bindings and set the behavior of the script engine
        initializeEngine();

        // Write the manifest file
        writeKubernetesManifestFile(k8s_manifest);

        // Mode 1: Only create the k8s resource(s)
        if (k8sCreateOnly) {
            createKubernetesResources();
        }

        // Mode 2: Create, stream logs and delete the k8s resource(s)
        else if (!k8sCreateOnly && !k8sDeleteOnly) {
            createKubernetesResources();
            switch (k8sResourcesList.size()) { // if multiple k8s resources have been created, need to select one for logs streaming
                case 0:
                    throw new ScriptException("No k8s resources were created; cannot stream logs.");
                case 1:
                    streamKubernetesResourceLogs(k8sResourcesList.get(0));
                    break;
                default:
                    // more than one k8s resources has been created, and we can only stream logs for one
                    streamKubernetesResourceLogs(chooseKubernetesResourceToStream());
                    break;
            }
            cleanKubernetesResources();
        }

        // Mode 3: only delete the k8s resource(s)
        else if (k8sDeleteOnly) {
            cleanKubernetesResources();
        }

        // Delete manifest file
        deleteKubernetesManifestFile();

        // Clean exit
        Object resultValue = true;
        return resultValue;
    }

    /**********************************************/
    /* Kubernetes script engine auxiliary methods */
    /**********************************************/

    private void initializeEngine() {
        bindings.addBindingsAsEngineMetadata(context);
        setScriptEngineBehaviorFromEnv();
    }

    private void writeKubernetesManifestFile(String k8s_manifest) {
        // Substitute workflow/task variable to real values onto the k8s manifest file
        String k8s_manifest_with_substitution = VariablesSubstitutor.replaceRecursively(k8s_manifest,
                                                                                        bindings.getK8sEngineMetadata());
        Map<String, String> environment = bindings.getK8sEngineMetadata();
        try {
            // Writing the newly generated manifest
            k8sManifestFile = new GenericFileWriter().forceFileToDisk(k8s_manifest_with_substitution,
                                                                      K8S_MANIFEST_FILE_NAME);
        } catch (IOException e) {
            log.error("Failed to write content to kubernetes manifest file: ", e);
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
                deleteKubernetesManifestFile();
                throw new ScriptException("Kubernetes resources creation has failed. Exit code " + exitValue +
                                          " . \nkubectl output is: " + kubectl_output);
            }
            // Creation was successful, going to parse the json output of 'kubectl' to keep track of the newly created resources
            JsonStreamParser parser = new JsonStreamParser(kubectl_output);
            // Retrieve created resource(s) info (kind, resource name & namespace)
            Consumer<JsonElement> k8sResourceJsonParse = (
                    JsonElement k8sResource) -> parseKubernetesResourceJson(k8sResource);
            parser.forEachRemaining(k8sResourceJsonParse);
        } catch (IOException e) {
            cleanKubernetesResources();
            deleteKubernetesManifestFile();
            throw new ScriptException("I/O error when trying to create kubernetes resources. Exiting.\nException: " +
                                      e);
        } catch (InterruptedException e1) {
            cleanKubernetesResources();
            deleteKubernetesManifestFile();
            throw new ScriptException("Interrupted when trying to create kubernetes resources. Exiting.\nException: " +
                                      e1);
        }
    }

    private void parseKubernetesResourceJson(JsonElement resource_json) {
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

    private KubernetesResource chooseKubernetesResourceToStream() {
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

    private void streamKubernetesResourceLogs(KubernetesResource resource) throws ScriptException {
        log.debug("Kubectl logs thread started.");

        while (true) { // In case of early call to logs (e.g. during ContainerCreating state)

            try {

                String[] kubectlCommand = kubernetesCommandCreator.createKubectlLogsCommand(resource.getKind(),
                                                                                            resource.getName(),
                                                                                            resource.getNamespace());

                // Override current process builder
                ProcessBuilder processBuilder = SingletonKubernetesProcessBuilderFactory.getInstance()
                                                                                        .getProcessBuilder(kubectlCommand);

                Process process = processBuilder.start();

                //Wait for the process to exit
                process.waitFor();

                if (process.exitValue() == 0) {
                    log.info(" ");
                    log.info("[Output from kubernetes resource " + resource.getKind() + '/' + resource.getName() +
                             ": ]");
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
                deleteKubernetesManifestFile();
                throw new ScriptException("Interrupted when trying to stream logs of kubernetes resources. Exiting.\nException: " +
                                          e);
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

    private void deleteKubernetesManifestFile() {
        if (k8sManifestFile != null) {
            boolean deleted = k8sManifestFile.delete();
            if (!deleted) {
                log.warn("File: " + k8sManifestFile.getAbsolutePath() + " was not deleted.");
            } else {
                k8sManifestFile = null;
            }
        }
    }

    private void setScriptEngineBehaviorFromEnv() {
        Map<String, String> environment = bindings.getK8sEngineMetadata();
        // Parsing the optional parameters of the script engine provided as generic info
        if (environment != null) {
            if (environment.containsKey(GI_K8S_CREATE_ONLY)) {
                k8sCreateOnly = Boolean.valueOf(environment.get(GI_K8S_CREATE_ONLY));
            }
            if (environment.containsKey(GI_K8S_DELETE_ONLY)) {
                k8sDeleteOnly = Boolean.valueOf(environment.get(GI_K8S_DELETE_ONLY));
            }
            if (environment.containsKey(GI_K8S_STREAM_LOGS)) {
                k8sDeleteOnly = Boolean.valueOf(environment.get(GI_K8S_STREAM_LOGS));
            }
            if (environment.containsKey(GI_K8S_RESOURCE_TO_STREAM)) {
                k8sResourceToStream = environment.get(GI_K8S_RESOURCE_TO_STREAM);
            }
        }

    }

    /*********************************/
    /* Script engine general methods */
    /*********************************/

    @Override
    public Object eval(Reader reader, ScriptContext context) throws ScriptException {

        StringWriter stringWriter = new StringWriter();

        try {
            KubernetesProcessBuilderUtilities.pipe(reader, stringWriter);
        } catch (IOException e) {
            log.warn("Failed to convert Reader into StringWriter. Not possible to execute Kubernetes task.");
            log.debug("Failed to convert Reader into StringWriter. Not possible to execute Kubernetes task.", e);
        }

        // Needed to guarantee cleanup in case of kill
        Thread shutdownHook = null;

        //Add a shutdownHook to remove the kubernetes pod in case of kill
        shutdownHook = new Thread() {
            @Override
            public void run() {
                cleanKubernetesResources();
                deleteKubernetesManifestFile();
            }
        };
        Runtime.getRuntime().addShutdownHook(shutdownHook);
        Object return_val = null;

        try {
            return_val = eval(stringWriter.toString(), context);
        } catch (Exception e) {

        } finally {
            if (shutdownHook != null) {
                Runtime.getRuntime().removeShutdownHook(shutdownHook);
            }
        }

        return return_val;
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
