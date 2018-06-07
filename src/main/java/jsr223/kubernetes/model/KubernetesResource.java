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
package jsr223.kubernetes.model;

import java.util.stream.Stream;

import lombok.AllArgsConstructor;
import lombok.Getter;


@AllArgsConstructor
public class KubernetesResource {

    private final String[] logStreamableResourceKind = { "Job", "Pod", "Deployment" };

    @Getter
    private String kind;

    @Getter
    private String name;

    @Getter
    private String namespace;

    // Check if we can perform 'kubectl logs' on this resource
    public boolean isLogStreamable() {
        return Stream.of(logStreamableResourceKind)
                     .anyMatch(kind -> kind.toLowerCase().equals(this.kind.toLowerCase()));
    }

}
