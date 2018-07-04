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

import java.util.Map;


public class VariablesSubstitutor {

    public static final int MAXIMUM_DEPTH = 5;

    public static final String K8S_MANIFEST_CUSTOM_VAR_SYNTAX_PREF = "${";

    public static final String K8S_MANIFEST_CUSTOM_VAR_SYNTAX_SUFF = "}";

    public static String replaceRecursively(final String value, Map<String, String> substitutes) {
        boolean anyReplacement;
        String output = value;
        int depthCount = 0;
        do {
            anyReplacement = false;
            depthCount++;

            for (Map.Entry<String, String> variable : substitutes.entrySet()) {
                System.out.println("I'm looking for " + variable.getKey() + " into \n\n" + output + "\n\n");
                if (output.contains(K8S_MANIFEST_CUSTOM_VAR_SYNTAX_PREF + variable.getKey() +
                                    K8S_MANIFEST_CUSTOM_VAR_SYNTAX_SUFF)) {
                    String newOutput = output.replace(K8S_MANIFEST_CUSTOM_VAR_SYNTAX_PREF + variable.getKey() +
                                                      K8S_MANIFEST_CUSTOM_VAR_SYNTAX_SUFF, variable.getValue());
                    anyReplacement = anyReplacement || !newOutput.equals(output);
                    output = newOutput;
                    System.out.println("I found.");
                }
            }

        } while (anyReplacement && depthCount < MAXIMUM_DEPTH);
        return output;
    }
}
