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
import java.util.Properties;

import org.apache.log4j.Logger;

import lombok.Getter;


/**
 * @author ActiveEon Team
 * @since 17/05/2018
 */
public class KubernetesPropertyLoader {

    private static final Logger log = Logger.getLogger(KubernetesPropertyLoader.class);

    public final static String CONFIGURATION_FILE = "config/scriptengines/kubernetes/kubernetes.properties";

    @Getter
    private final String kubectlCommand;

    @Getter
    private final String kubectlConfig;

    @Getter
    private final String kubectlKey;

    private final Properties properties;

    private KubernetesPropertyLoader() {
        properties = new Properties();
        try {
            log.debug("Load properties from configuration file: " + CONFIGURATION_FILE);
            properties.load(getClass().getClassLoader().getResourceAsStream(CONFIGURATION_FILE));
        } catch (IOException | NullPointerException e) {
            log.info("Configuration file " + CONFIGURATION_FILE + " not found. Standard values will be used.");
            log.debug("Configuration file " + CONFIGURATION_FILE + " not found. Standard values will be used.", e);
        }

        // Get property, specify default value
        this.kubectlCommand = properties.getProperty("kubectl.command", "/usr/local/bin/kubectl");
        this.kubectlConfig = properties.getProperty("kubectl.config", "~/.kube/config");
        this.kubectlKey = properties.getProperty("kubectl.key", "~/.kube/config/id_rsa");
    }

    public static KubernetesPropertyLoader getInstance() {
        return KubernetesPropertyLoaderHolder.INSTANCE;
    }

    /**
     * Initializes KubernetesPropertyLoader.
     *
     * KubernetesPropertyLoaderHolder is loaded on the first execution of KubernetesPropertyLoader.getInstance()
     * or the first access to KubernetesLoaderHolder.INSTANCE, not before.
     **/
    private static class KubernetesPropertyLoaderHolder {
        private static final KubernetesPropertyLoader INSTANCE = new KubernetesPropertyLoader();

        private KubernetesPropertyLoaderHolder() {
        }
    }
}
