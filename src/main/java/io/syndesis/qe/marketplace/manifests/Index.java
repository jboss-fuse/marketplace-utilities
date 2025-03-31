package io.syndesis.qe.marketplace.manifests;

import static io.syndesis.qe.marketplace.util.HelperFunctions.readResource;
import static io.syndesis.qe.marketplace.util.HelperFunctions.waitFor;

import org.apache.commons.io.IOUtils;

import org.json.JSONObject;
import org.yaml.snakeyaml.Yaml;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;
import java.util.function.Predicate;

import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.client.dsl.base.CustomResourceDefinitionContext;
import io.fabric8.kubernetes.client.utils.Serialization;
import io.fabric8.openshift.client.OpenShiftClient;
import io.syndesis.qe.marketplace.openshift.OpenShiftService;
import io.syndesis.qe.marketplace.quay.QuayUser;
import io.syndesis.qe.marketplace.util.HelperFunctions;
import lombok.Getter;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class Index {
    private final String name;
    @Getter
    private String ocpName;
    private List<Bundle> bundles;
    private Opm opm;
    public static final String CONTAINER_TOOL = System.getProperty("marketplace.container.tool", "docker");
    static final String MARKETPLACE_NAMESPACE = "openshift-marketplace";
    private static File configFile;

    private QuayUser quayUser;
    private final OpenShiftService ocpService;

    Index(String name, OpenShiftService ocpService, Opm opm, QuayUser quayUser) {
        this.name = name;
        bundles = new ArrayList<>();
        this.opm = opm;
        this.quayUser = quayUser;
        this.ocpService = ocpService;
    }

    void createConfig(QuayUser user) {
        try {
            File configFolder = Files.createTempDirectory("marketplace-docker-config").toFile();
            configFile = new File(configFolder, "config.json");
            String auth = user.getUserName() + ":" + user.getPassword();
            String encodedAuth = new String(Base64.getEncoder().encode(auth.getBytes()));
            String contents = "{\"auths\": {\"quay.io\": {\"auth\": \"" + encodedAuth + "\"}}}";
            try (FileWriter fileWriter = new FileWriter(configFile)) {
                IOUtils.write(contents, fileWriter);
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    void pull() {
        HelperFunctions.runCmd(CONTAINER_TOOL, "pull", this.name);
    }

    public Bundle addBundle(String bundleName) {
        Bundle bundle = new Bundle(bundleName, this, ocpService);
        if (bundles.isEmpty()) {
            opm.runOpmCmd("index", "add", "--bundles=" + bundleName, "--tag=" + this.name, "--container-tool=" + CONTAINER_TOOL);
        } else {
            opm.runOpmCmd("index", "add", "--bundles=" + bundleName, "--tag=" + this.name, "--container-tool=" + CONTAINER_TOOL, "--from-index=" + name);
        }
        bundles.add(bundle);
        push();
        return bundle;
    }

    public void addBundles(String... names) {
        for (String name : names) {
            addBundle(name);
        }
    }

    @SneakyThrows
    private void push() {
        if (configFile == null || !configFile.exists()) {
            createConfig(quayUser);
        }
        if (CONTAINER_TOOL.equalsIgnoreCase("docker")) {
            HelperFunctions.runCmd(CONTAINER_TOOL, "--config", configFile.getParent(), "push", name);
        } else {
            HelperFunctions.runCmd(CONTAINER_TOOL, "push", name, "--authfile", configFile.getAbsolutePath());
        }
    }

    private static CustomResourceDefinitionContext catalogSourceIndex() {
        return new CustomResourceDefinitionContext.Builder()
            .withGroup("operators.coreos.com")
            .withVersion("v1alpha1")
            .withName("CatalogSource")
            .withPlural("catalogsources")
            .withScope("Namespaced")
            .build();
    }

    public void addIndexToCluster(String catalogName) throws IOException, TimeoutException, InterruptedException {
        OpenShiftClient ocp = ocpService.getClient();

        String catalogSource = null;
        try {
            catalogSource = readResource("openshift/create-operatorsourceindex.yaml");
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        this.ocpName = catalogName;
        catalogSource = catalogSource.replaceAll("IMAGE", name)
            .replaceAll("DISPLAY_NAME", catalogName)
            .replaceAll("NAME", catalogName);

        JSONObject catalogSourceJsonObj = new JSONObject((Map<String, Object>) new Yaml().load(catalogSource));
        String cataloSourceJson = catalogSourceJsonObj.toString();
        GenericKubernetesResource k8resource = Serialization.jsonMapper().readValue(cataloSourceJson, GenericKubernetesResource.class);
        ocp.genericKubernetesResources(catalogSourceIndex()).inNamespace(MARKETPLACE_NAMESPACE).resource(k8resource).createOrReplace();

        Predicate<Pod> podFound = pod ->
            pod.getMetadata().getName().startsWith(catalogName)
                && "Running".equalsIgnoreCase(pod.getStatus().getPhase());
        waitFor(() -> ocp.pods().inNamespace("openshift-marketplace").list()
            .getItems().stream().anyMatch(podFound), 5, 60 * 1000);
    }

    public void removeIndexFromCluster() {
        try {
            GenericKubernetesResource k8resource = Serialization.jsonMapper().readValue(ocpName, GenericKubernetesResource.class);
            ocpService.getClient().genericKubernetesResources(catalogSourceIndex()).inNamespace(MARKETPLACE_NAMESPACE).resource(k8resource).createOrReplace();
        } catch (IOException e) {
            log.warn("Unable to remove index: ", e);
        }
    }
}
