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

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

import javax.script.ScriptContext;

import lombok.Getter;
import lombok.NoArgsConstructor;


@NoArgsConstructor
public class BindingUtils {

    // GI, bindings and variables
    @Getter
    Map<String, String> k8sEngineMetadata;

    private void addMapBindingAsEngineMetadata(String bindingKey, Map<?, ?> bindingValue,
            Map<String, String> environment) {
        for (Map.Entry<?, ?> entry : ((Map<?, ?>) bindingValue).entrySet()) {
            environment.put(bindingKey + "_" + entry.getKey(),
                            (entry.getValue() == null ? "" : toEmptyStringIfNull(entry.getValue())));
        }
    }

    public static String toEmptyStringIfNull(Object value) {
        return value == null ? "" : value.toString();
    }

    private void addCollectionBindingAsEngineMetadata(String bindingKey, Collection bindingValue,
            Map<String, String> environment) {
        Object[] bindingValueAsArray = bindingValue.toArray();
        addArrayBindingAsEnvironmentVariable(bindingKey, bindingValueAsArray, environment);
    }

    private void addArrayBindingAsEnvironmentVariable(String bindingKey, Object[] bindingValue,
            Map<String, String> environment) {
        for (int i = 0; i < bindingValue.length; i++) {
            environment.put(bindingKey + "_" + i,
                            (bindingValue[i] == null ? "" : toEmptyStringIfNull(bindingValue[i].toString())));
        }
    }

    public void addBindingsAsEngineMetadata(ScriptContext scriptContext) {

        // Init.
        k8sEngineMetadata = new HashMap();

        for (Map.Entry<String, Object> binding : scriptContext.getBindings(ScriptContext.ENGINE_SCOPE).entrySet()) {
            String bindingKey = binding.getKey();
            Object bindingValue = binding.getValue();

            if (bindingValue instanceof Object[]) {
                addArrayBindingAsEnvironmentVariable(bindingKey, (Object[]) bindingValue, k8sEngineMetadata);
            } else if (bindingValue instanceof Collection) {
                addCollectionBindingAsEngineMetadata(bindingKey, (Collection) bindingValue, k8sEngineMetadata);
            } else if (bindingValue instanceof Map) {
                addMapBindingAsEngineMetadata(bindingKey, (Map<?, ?>) bindingValue, k8sEngineMetadata);
            } else {
                k8sEngineMetadata.put(bindingKey, toEmptyStringIfNull(binding.getValue()));
            }
        }
    }
}
