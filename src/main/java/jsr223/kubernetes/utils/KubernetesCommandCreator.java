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
package jsr223.kubernetes.utils;

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

/**
 * @author ActiveEon Team
 * @since 17/05/2018
 */

import java.util.ArrayList;
import java.util.List;

import lombok.NoArgsConstructor;


@NoArgsConstructor
public class KubernetesCommandCreator {

    /* Constants */
    // kubectl directive
    public static final String START_K8S_RESOURCES = "create";

    public static final String STOP_AND_REMOVE_K8S_RESOURCES = "delete";

    public static final String LOGS_K8S_RESOURCES = "logs";

    public static final String GET_K8S_RESOURCES = "get";

    // kubectl switches
    public static final String FILENAME_PARAM_SWITCH = "-f";

    public static final String TAILF_PARAM_SWITCH = "-f";

    public static final String OUTPUT_FORMAT_SWITCH = "-o";

    // kubectl output format
    public static final String NAME_OUTPUT_FORMAT = "name";

    public static final String JSON_STATUS_OUTPUT_FORMAT = "jsonpath=\"{..status}\"";

    /**
     * This method creates a bash command to delete the resources specified in the k8s manifest file
     * Command syntax is: "kubectl delete -f [K8S_MANIFEST_FILE]".
     *
     * @return A String array which contains the command as a separate @String and each
     * argument as a separate String.
     */
    public String[] createKubectlDeleteCommand(String k8sManifestFileNameAndPath) {
        List<String> command = new ArrayList<>();

        // Add kubectl command
        addKubectlCommand(command);

        // Add kubectl directive "delete"
        command.add(STOP_AND_REMOVE_K8S_RESOURCES);

        // Add filename param switch
        command.add(FILENAME_PARAM_SWITCH);

        // Add filename
        command.add(k8sManifestFileNameAndPath);

        return command.toArray(new String[command.size()]);
    }

    /**
     * This method creates a bash command to create the resources specified in the k8s manifest file
     * Command syntax is: "kubectl create -f [K8S_MANIFEST_FILE]".
     *
     * @return A String array which contains the command as a separate @String and each
     * argument as a separate String.
     */
    public String[] createKubectlCreateCommand(String k8sManifestFileNameAndPath) {
        List<String> command = new ArrayList<>();

        // Add kubectl command
        addKubectlCommand(command);

        // Add kubectl directive "create"
        command.add(START_K8S_RESOURCES);

        // Add filename param switch
        command.add(FILENAME_PARAM_SWITCH);

        // Add filename
        command.add(k8sManifestFileNameAndPath);

        // Make kubectl return the name of the newly created resource
        command.add(OUTPUT_FORMAT_SWITCH);
        command.add(NAME_OUTPUT_FORMAT);

        return command.toArray(new String[command.size()]);
    }

    /**
     * Adds kubectl command to the given list.
     *
     * @param command List which gets the command(s) added.
     */
    private void addKubectlCommand(List<String> command) {
        // Add kubectl command
        command.add(KubernetesPropertyLoader.getInstance().getKubectlCommand());
    }

    public String[] createKubectlLogsCommand(String k8sResourceName) {
        List<String> command = new ArrayList<>();

        // Add kubectl command
        addKubectlCommand(command);

        // Add kubectl directive "create"
        command.add(LOGS_K8S_RESOURCES);

        // Add filename param switch
        command.add(k8sResourceName);

        // Add tailf-like switch
        command.add(TAILF_PARAM_SWITCH);

        return command.toArray(new String[command.size()]);
    }

    public String[] createKubectlGetStateCommand(String k8sResourceName) {
        List<String> command = new ArrayList<>();

        // Add kubectl command
        addKubectlCommand(command);

        // Add kubectl directive "create"
        command.add(GET_K8S_RESOURCES);

        // Add filename param switch
        command.add(k8sResourceName);

        // Make kubectl returns the status line only
        command.add(OUTPUT_FORMAT_SWITCH);
        command.add(JSON_STATUS_OUTPUT_FORMAT);

        return command.toArray(new String[command.size()]);
    }
}
