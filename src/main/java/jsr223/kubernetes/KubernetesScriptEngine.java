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
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.stream.Collectors;

import javax.script.AbstractScriptEngine;
import javax.script.Bindings;
import javax.script.ScriptContext;
import javax.script.ScriptEngineFactory;
import javax.script.ScriptException;
import javax.script.SimpleBindings;

import org.apache.log4j.Logger;
import org.ow2.proactive.scheduler.common.SchedulerConstants;

import jsr223.kubernetes.entrypoint.EntryPoint;
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

    private CountDownLatch countDownLatch = new CountDownLatch(1);

    private static final Logger log = Logger.getLogger(KubernetesScriptEngine.class);

    // Constant
    public static final String K8S_MANIFEST_FILE_NAME = "k8s-manifest.yml";

    // Utils
    private GenericFileWriter k8SManifestFileWriter = new GenericFileWriter();

    private KubernetesCommandCreator kubernetesCommandCreator = new KubernetesCommandCreator();

    private KubernetesProcessBuilderUtilities processBuilderUtilities = new KubernetesProcessBuilderUtilities();

    private File k8sManifestFile = null;

    private Map<String, String> genericInfo;

    Bindings bindingsShared;

    @Override
    public Object eval(String k8s_manifest, ScriptContext context) throws ScriptException {

        // Step 0: Populate the bindings and the Generic Info
        populateBindingAndGI();

        // Step 1: Creating Kubernetes resource
        String k8sResourceName = createKubernetesResources(k8s_manifest);

        // Step 2: log output
        kubecltLog(k8sResourceName);

        // Step 3: cleaning resources and artifacts
        cleanResources();

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
    }

    private String createKubernetesResources(String k8s_manifest) throws ScriptException {
        log.info("Creating Kubernetes resources from manifest.");

        // Write k8s manifest to file
        try {
            k8sManifestFile = k8SManifestFileWriter.forceFileToDisk(k8s_manifest, K8S_MANIFEST_FILE_NAME);
        } catch (IOException e) {
            log.warn("Failed to write content to kubernetes manifest file: ", e);
        }

        // Prepare kubectl command
        String[] kubectlCommand = kubernetesCommandCreator.createKubectlCreateCommand(K8S_MANIFEST_FILE_NAME);

        //Create a process builder
        Process process = null;
        ProcessBuilder processBuilder = SingletonKubernetesProcessBuilderFactory.getInstance()
                                                                                .getProcessBuilder(kubectlCommand);

        try {
            //Run the 'kubectl' process
            process = processBuilder.start();
            int exitValue = process.waitFor();
            if (exitValue != 0) {
                stopAndRemoveK8sResources().waitFor();
                throw new ScriptException("Kubernetes resources creation has failed with exit code " + exitValue);
            }
            try (BufferedReader buffer = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                return buffer.lines().collect(Collectors.joining(" "));
            }
        } catch (IOException e) {
            cleanResources();
            throw new ScriptException("I/O error when trying to create kubernetes resources. Exiting. Exception: " + e);
        } catch (InterruptedException e1) {
            cleanResources();
            throw new ScriptException("Interrupted when trying to create kubernetes resources. Exiting. Exception: " +
                                      e1);
        }
    }

    private Thread startKubectlLogsThread(String k8sResourceName) {
        log.info("Attaching kubernetes jobs output logger:");
        log.info(" ");
        String finalK8sResourceName = k8sResourceName;
        Runnable kubectl_logs_thread = () -> {
            kubecltLog(finalK8sResourceName);
        };
        Thread t = new Thread(kubectl_logs_thread);
        t.start();
        return t;
    }

    private void kubecltLog(String k8sResourceName) {
        log.debug("Kubectl logs thread started.");

        while (true) { // In case of early call to logs (e.g. during ContainerCreating state)

            try {

                String[] kubectlCommand = kubernetesCommandCreator.createKubectlLogsCommand(k8sResourceName, true);

                // Override current process builder
                ProcessBuilder processBuilder = SingletonKubernetesProcessBuilderFactory.getInstance()
                                                                                        .getProcessBuilder(kubectlCommand);

                Process process = processBuilder.start();

                //Wait for the process to exit
                process.waitFor();

                if (process.exitValue() == 0) {
                    countDownLatch.countDown();
                    log.info(" ");
                    log.info("[Output from kubernetes resource " + k8sResourceName + ": ]");
                    processBuilderUtilities.attachStreamsToProcess(process,
                                                                   context.getWriter(),
                                                                   context.getErrorWriter(),
                                                                   null);
                    break;
                } else {
                    Thread.sleep(1000); // wait for the kubernetes resource to be in appropriate state for log streaming
                }

            } catch (InterruptedException e) { // TODO: define own exception KubernetesJobCompletedException
                log.warn("Interrupted when trying to stream kubernetes resources logs. Stopping log streaming. Exception: " +
                         e);
                return;
            } catch (IOException e) {
                log.warn("I/O error when trying to stream kubernetes resources logs. Stopping log streaming. Exception: " +
                         e);
            }
        }

    }

    private void cleanResources() {
        try {
            stopAndRemoveK8sResources().waitFor();
        } catch (InterruptedException e) {
            log.warn("Interrupted when trying to delete/clean kubernetes resources. Exiting. Exception: " + e);
        } catch (IOException e) {
            log.warn("I/O error when trying to delete/clean kubernetes resources. Exiting. Exception: " + e);
        }

        //Delete Kubernetes manifest file
        if (k8sManifestFile != null) {
            boolean deleted = k8sManifestFile.delete();
            if (!deleted) {
                log.warn("File: " + k8sManifestFile.getAbsolutePath() + " was not deleted.");
            } else {
                k8sManifestFile = null;
            }
        }
    }

    private Process stopAndRemoveK8sResources() throws IOException {
        return SingletonKubernetesProcessBuilderFactory.getInstance()
                                                       .getProcessBuilder(kubernetesCommandCreator.createKubectlDeleteCommand(K8S_MANIFEST_FILE_NAME))
                                                       .start();
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
