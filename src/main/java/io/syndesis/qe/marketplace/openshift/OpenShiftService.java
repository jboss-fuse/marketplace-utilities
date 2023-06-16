package io.syndesis.qe.marketplace.openshift;

import static io.syndesis.qe.marketplace.util.HelperFunctions.waitFor;

import org.apache.commons.codec.binary.Base64;
import org.apache.commons.io.IOUtils;
import org.json.JSONArray;
import org.json.JSONObject;
import org.yaml.snakeyaml.Yaml;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jayway.jsonpath.DocumentContext;
import com.jayway.jsonpath.JsonPath;

import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;

import cz.xtf.core.openshift.OpenShift;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.api.model.SecretBuilder;
import io.fabric8.kubernetes.api.model.ServiceAccount;
import io.fabric8.kubernetes.api.model.ServiceAccountBuilder;
import io.fabric8.kubernetes.client.KubernetesClientException;
import io.fabric8.kubernetes.client.dsl.base.CustomResourceDefinitionContext;

import io.fabric8.openshift.api.model.OperatorHub;
import io.fabric8.openshift.api.model.operatorhub.lifecyclemanager.v1.CSVDescription;
import io.fabric8.openshift.api.model.operatorhub.lifecyclemanager.v1.PackageChannel;
import io.fabric8.openshift.api.model.operatorhub.lifecyclemanager.v1.PackageChannelBuilder;
import io.fabric8.openshift.api.model.operatorhub.lifecyclemanager.v1.PackageChannelFluent;
import io.fabric8.openshift.api.model.operatorhub.lifecyclemanager.v1.PackageManifest;
import io.fabric8.openshift.api.model.operatorhub.lifecyclemanager.v1.PackageManifestBuilder;
import io.fabric8.openshift.api.model.operatorhub.v1alpha1.CatalogSource;
import lombok.Getter;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class OpenShiftService {

    private final OpenShift openShiftClient;

    @Getter
    private final OpenShiftConfiguration openShiftConfiguration;
    @Getter
    private final OpenShiftUser adminUser;

    @Getter
    private OpenShift openShiftClientAsRegularUser;

    public OpenShiftService(
        OpenShiftConfiguration openShiftConfiguration,
        OpenShiftUser adminOpenShiftUser,
        OpenShiftUser regularOpenShiftUser) {

        this.openShiftConfiguration = openShiftConfiguration;

        this.openShiftClient = OpenShift.get(
            adminOpenShiftUser.getApiUrl(),
            openShiftConfiguration.getNamespace(),
            adminOpenShiftUser.getUserName(),
            adminOpenShiftUser.getPassword()
        );
        this.adminUser = adminOpenShiftUser;

        if (regularOpenShiftUser != null) {
            openShiftClientAsRegularUser = OpenShift.get(
                regularOpenShiftUser.getApiUrl(),
                openShiftConfiguration.getNamespace(),
                regularOpenShiftUser.getUserName(),
                regularOpenShiftUser.getPassword()
            );
        }
    }

    public void refreshOperators() {
        openShiftClient.pods().inNamespace("openshift-marketplace")
            .delete();
    }

    @SneakyThrows
    public void setupImageContentSourcePolicy() {
        if (openShiftConfiguration.getIcspFile() == null || openShiftConfiguration.getDockerRegistry() == null) {
            log.info("ICSP was not set up, skipping creation.");
            return;
        }
        CustomResourceDefinitionContext mcpContext = new CustomResourceDefinitionContext.Builder()
            .withVersion("v1")
            .withGroup("machineconfiguration.openshift.io")
            .withScope("Cluster")
            .withPlural("machineconfigpools")
            .build();
        //make sure nodes are not being updated
        waitFor(() -> {
            try {
                JSONObject mcpList = new JSONObject(openShiftClient.customResource(mcpContext).list());
                final JSONArray mcps = mcpList.getJSONArray("items");
                boolean updated = false;
                for (int i = 0; i < mcps.length(); i++) {
                    final DocumentContext documentContext = JsonPath.parse(mcps.getJSONObject(i).toString());
                    boolean status =
                        documentContext.read(".status.conditions[?(@.type==\"Updated\")].status").toString().toLowerCase().contains("true");
                    updated |= status;
                }
                return updated;
            } catch (Exception ex) {
                log.error("Got exception in the wait: ", ex);
                return false;
            }
        }, 10, 3000);
        final CustomResourceDefinitionContext icspContext =
            new CustomResourceDefinitionContext.Builder().withGroup("operator.openshift.io").withPlural("imagecontentsourcepolicies")
                .withScope("Cluster").withVersion("v1alpha1").build();
        final String icspSource = IOUtils.toString(new URL(openShiftConfiguration.getIcspFile()), StandardCharsets.UTF_8);
        final String icspName = ((Map<String, String>) ((Map<String, Object>) new Yaml().load(icspSource)).get("metadata")).get("name");
        try {
            openShiftClient.customResource(icspContext).get(icspName);
        } catch (KubernetesClientException ignored) {
            log.info("ICSP was not found, creating new!");
            try {
                openShiftClient.customResource(icspContext).createOrReplace(icspSource);
            } catch (Exception ex) {
                log.error("Something went wrong while setting up ICSP, would you mind setting it manually?", ex);
                throw new RuntimeException(ex);
            }
            waitForMCP();
        }

        final String globalPullSecret = new String(Base64.decodeBase64(
            openShiftClient.secrets().inNamespace("openshift-config").withName("pull-secret").get().getData().get(".dockerconfigjson")));
        if (!globalPullSecret.contains(openShiftConfiguration.getDockerRegistry())) {
            final JSONObject secretJson = new JSONObject(globalPullSecret);
            JSONObject auth = new JSONObject();
            String authString = openShiftConfiguration.getDockerUsername() + ":" + openShiftConfiguration.getDockerPassword();
            auth.put("username", openShiftConfiguration.getDockerUsername());
            auth.put("password", openShiftConfiguration.getDockerPassword());
            auth.put("auth", Base64.encodeBase64String(authString.getBytes(StandardCharsets.UTF_8)));
            secretJson.getJSONObject("auths").put(openShiftConfiguration.getDockerRegistry(), auth);
            log.info("Setting up global pull-secret");
            openShiftClient.secrets().inNamespace("openshift-config").withName("pull-secret").edit(
                    s -> new SecretBuilder(s)
                            .addToData(".dockerconfigjson", new String(Base64.encodeBase64(secretJson.toString().getBytes())))
                            .build()
            );
            waitForMCP();
        }
    }

    private void waitForMCP() throws InterruptedException, TimeoutException {
        CustomResourceDefinitionContext mcpContext = new CustomResourceDefinitionContext.Builder()
            .withVersion("v1")
            .withGroup("machineconfiguration.openshift.io")
            .withScope("Cluster")
            .withPlural("machineconfigpools")
            .build();

        log.info("Waiting for OCP to pick up new config, this might take a while...");
        waitFor(() -> {
            JSONObject mcpList = new JSONObject(openShiftClient.customResource(mcpContext).list());
            final JSONArray mcps = mcpList.getJSONArray("items");
            boolean updating = false;
            for (int i = 0; i < mcps.length(); i++) {
                final DocumentContext documentContext = JsonPath.parse(mcps.getJSONObject(i).toString());
                boolean status =
                    documentContext.read(".status.conditions[?(@.type==\"Updating\")].status").toString().toLowerCase().contains("true");
                updating |= status;
            }
            return updating;
        }, 10, 3000);

        log.info("Nodes started upgrading, this will also take a while...");
        waitFor(() -> {
            try {
                JSONObject mcpList = new JSONObject(openShiftClient.customResource(mcpContext).list());
                final JSONArray mcps = mcpList.getJSONArray("items");
                boolean updated = false;
                for (int i = 0; i < mcps.length(); i++) {
                    final DocumentContext documentContext = JsonPath.parse(mcps.getJSONObject(i).toString());
                    boolean status =
                        documentContext.read(".status.conditions[?(@.type==\"Updated\")].status").toString().toLowerCase().contains("true");
                    updated |= status;
                }
                return updated;
            } catch (Exception ex) {
                log.error("Got exception in the wait: ", ex);
                return false;
            }
        }, 10, 3000);
    }

    /**
     * Configures openshift-marketplace to pull from Quay and mirror internal registries
     *
     * @param pullSecretContent - base64 encoded Docker auths json
     */
    public void patchGlobalSecrets(String pullSecretContent) {
        Map<String, String> obligatoryMap = new HashMap<>();
        obligatoryMap.put(".dockerconfigjson", pullSecretContent);

        final Secret s = openShiftClient.inNamespace("openshift-marketplace").secrets().createOrReplace(
                new SecretBuilder()
                        .withType("kubernetes.io/dockerconfigjson")
                        .editOrNewMetadata()
                        .withName("quay-pull-secret")
                        .withNamespace("openshift-marketplace")
                        .endMetadata()
                        .withData(obligatoryMap)
                        .build()
                );

        openShiftClient.serviceAccounts().inNamespace("openshift-marketplace").withName("default").edit(
                serviceAccount -> new ServiceAccountBuilder(serviceAccount)
                        .addNewSecret()
                        .withName(s.getMetadata().getName())
                        .withNamespace(s.getMetadata().getNamespace())
                        .endSecret()
                        .build()
                );
    }

    public OpenShift getClient() {
        return openShiftClient;
    }

    /**
     * Retrieve the list of packages grouped by catalog sources.
     *
     * @return {@link Map} collection that contains catalog source name (i.e. String 'redhat-operators') as key
     * and {@link List} of {@link PackageManifest} as value.
     */
    public Map<String, List<PackageManifest>> getCatalog() {
        return loadCatalog();
    }

    /**
     * Invoke API to retrieve all CRD PackageManifest on 'openshift-marketplace' namespace
     *
     * @return Map, the catalog
     */
    private Map<String, List<PackageManifest>> loadCatalog() {

        CustomResourceDefinitionContext crds = new CustomResourceDefinitionContext.Builder()
            .withVersion("v1")
            .withKind("PackageManifest")
            .withGroup("packages.operators.coreos.com")
            .withScope("Namespaced")
            .withPlural("packagemanifests")
            .build();

        final ObjectMapper mapper = new ObjectMapper();

        Map<String, List<PackageManifest>> catalogContent = new HashMap<>();
        final JSONArray items = new JSONObject(openShiftClient.customResource(crds)
            .list("openshift-marketplace")).getJSONArray("items");
        int itemLen = items.length();
        if (itemLen > 0) {
            for (int i = 0; i < itemLen; i++) {
                JSONObject status = items.getJSONObject(i).getJSONObject("status");
                String catalogSource = status.getString("catalogSource");
                List<PackageManifest> manifests = catalogContent.getOrDefault(catalogSource, new ArrayList<>());
                try {
                    manifests.add(mapper.readValue(status.toString(), PackageManifest.class));
                } catch (JsonProcessingException e) {
                    log.error("Error while reading operator status: ", e);
                }
                catalogContent.put(catalogSource, manifests);
            }
        }

        return catalogContent;
    }

    /**
     * Retrieve the default channel of the operator with given name in given catalog source.
     *
     * @param source String, the catalog source
     * @param operatorName String, the operator name as found in the catalog
     * @return the {@link PackageChannel} found or {@link IllegalArgumentException} if not found
     */
    public PackageChannel getDefaultChannel(final String source, final String operatorName) {
        return getChannel(source, operatorName, null);
    }

    /**
     * Retrieve the channel by name, of the operator with given name in given catalog source.
     *
     * @param source String, the catalog source
     * @param operatorName String, the operator name as found in the catalog
     * @param channelName String, the channel name as found in the catalog
     * @return the {@link PackageChannel} found or {@link IllegalArgumentException} if not found
     */
    public PackageChannel getChannel(final String source, final String operatorName, final String channelName) {
        PackageManifest found = getCatalog().get(source).stream()
                .filter(pack -> operatorName.equals(pack.getAdditionalProperties().get("packageName")))
            .findFirst().orElseThrow(() -> new IllegalArgumentException("Operator " + operatorName + " not found"));
        String channelToUse = channelName != null ? channelName : (String) found.getAdditionalProperties().get("defaultChannel");
        Map<String, Object> channelMap = ((List<Map>) found.getAdditionalProperties().get("channels")).stream()
                .filter(ch -> channelToUse.equals(ch.get("name"))).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Channel " + channelToUse + " not found"));
        return new PackageChannel((String) channelMap.get("currentCSV"), null, (String) channelMap.get("name"));
    }
}
