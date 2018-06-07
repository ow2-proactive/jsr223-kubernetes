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

import java.io.IOException;
import java.io.StringWriter;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.log4j.Logger;

import jsr223.kubernetes.KubernetesScriptEngine;
import jsr223.kubernetes.processbuilder.KubernetesProcessBuilderFactory;
import jsr223.kubernetes.processbuilder.KubernetesProcessBuilderUtilities;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j;


@RequiredArgsConstructor
public class KubernetesClientVersionGetter {

    private static final Logger log = Logger.getLogger(KubernetesClientVersionGetter.class);

    @NonNull
    private KubernetesProcessBuilderUtilities processBuilderUtilities;

    /**
     * Retrieves the docker compose version.
     *
     * @return The currently installed version return by the docker compose command or an empty string
     * the version could not be determined.
     */
    public String getKubernetesComposeVersion(KubernetesProcessBuilderFactory factory) {
        if (factory == null) {
            return "Unknown";
        }

        String result = "Unknown"; // Empty string for empty result if version recovery fails

        ProcessBuilder processBuilder = factory.getProcessBuilder(KubernetesPropertyLoader.getInstance()
                                                                                          .getKubectlCommand(),
                                                                  "version");

        try {
            Process process = processBuilder.start();

            // Attach stream to std output of process
            StringWriter commandOutput = new StringWriter();
            processBuilderUtilities.attachStreamsToProcess(process, commandOutput, null, null);

            // Wait for process to exit
            process.waitFor();

            // Extract output
            result = commandOutput.toString();

            Pattern p = Pattern.compile("\\d\\.\\d\\.\\d");
            Matcher m = p.matcher(result);
            if (m.matches()) {
                log.info("kubectl client version is: " + m.group(0));
                return m.group(0);
            } else
                return "Unknown";
        } catch (IOException | InterruptedException | IndexOutOfBoundsException e) {
            log.debug("Failed to retrieve kubectl client version.", e);
            return "Unknown";
        }
    }

}
