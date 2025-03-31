package io.syndesis.qe.marketplace.manifests;

import static io.syndesis.qe.marketplace.manifests.Index.CONTAINER_TOOL;
import static io.syndesis.qe.marketplace.util.HelperFunctions.readResource;
import static io.syndesis.qe.marketplace.util.HelperFunctions.waitFor;

import static com.jayway.jsonpath.Criteria.where;

import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.client.utils.Serialization;
import io.syndesis.qe.marketplace.openshift.OpenShiftService;
import io.syndesis.qe.marketplace.util.HelperFunctions;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.io.IOUtils;

import org.assertj.core.api.Assertions;
import org.assertj.core.api.SoftAssertions;
import org.json.JSONArray;
import org.json.JSONObject;
import org.yaml.snakeyaml.Yaml;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientConfig;
import com.github.dockerjava.core.DockerClientImpl;
import com.github.dockerjava.okhttp.OkDockerHttpClient;
import com.github.dockerjava.transport.DockerHttpClient;
import com.jayway.jsonpath.DocumentContext;
import com.jayway.jsonpath.Filter;
import com.jayway.jsonpath.JsonPath;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import cz.xtf.core.openshift.OpenShift;
import io.fabric8.kubernetes.client.dsl.base.CustomResourceDefinitionContext;
import lombok.Getter;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class Bundle {
    @Getter
    private final String imageName;
    @Getter
    private Map<String, String> annotations;
    @Getter
    private JSONObject csv;

    @Getter
    private List<String> crds;
    private Index index;

    private String subscriptionName;

    private final OpenShiftService ocpService;

    Bundle(String imageName, Index index, OpenShiftService ocpService) {
        this.imageName = imageName;
        this.index = index;
        this.ocpService = ocpService;
        readMetadata();
    }

    private static CustomResourceDefinitionContext subscriptionContext() {
        return new CustomResourceDefinitionContext.Builder()
            .withGroup("operators.coreos.com")
            .withPlural("subscriptions")
            .withScope("Namespaced")
            .withVersion("v1alpha1")
            .build();
    }

    private static CustomResourceDefinitionContext installPlanContext() {
        return new CustomResourceDefinitionContext.Builder()
            .withGroup("operators.coreos.com")
            .withPlural("installplans")
            .withScope("Namespaced")
            .withVersion("v1alpha1")
            .build();
    }

    private static void unTar(TarArchiveInputStream tis, File destFolder) throws IOException {
        TarArchiveEntry tarEntry = null;
        while ((tarEntry = tis.getNextTarEntry()) != null) {
            if (tarEntry.isDirectory()) {
                new File(destFolder, tarEntry.getName()).mkdirs();
            } else {
                File result = new File(destFolder, tarEntry.getName());
                result.getParentFile().mkdirs();
                FileOutputStream fos = new FileOutputStream(result);
                IOUtils.copy(tis, fos);
                if (result.getName().endsWith(".tar")) {
                    unTar(new TarArchiveInputStream(new FileInputStream(result)), destFolder);
                }
                fos.close();
            }
        }
        tis.close();
    }

    @SneakyThrows
    private static String readFile(String path) {
        return IOUtils.toString(new FileInputStream(path), Charset.defaultCharset());
    }

    private void consumeManifestFolder(File manifestFolder) {
        String[] manifests = manifestFolder.list();
        Optional<String> csvPath = Stream.of(manifests)
            .filter(s -> s.contains("clusterserviceversion.yaml"))
            .findFirst();
        if (csvPath.isPresent()) {
            String csvSource = readFile(Paths.get(manifestFolder.getAbsolutePath(), csvPath.get()).toString());

            csv = new JSONObject((Map<String, Object>) new Yaml().load(csvSource));
        } else {
            throw new IllegalStateException(
                "A csv entry is missing from the bundle " + imageName + " take a look at folder " + manifestFolder.getParentFile().toString());
        }
        crds = Stream.of(manifests)
            .filter(s -> s.contains("crd.yaml"))
            .map(s -> Paths.get(manifestFolder.getAbsolutePath(), s).toString())
            .map(Bundle::readFile)
            .collect(Collectors.toList());
    }

    @SneakyThrows
    private void readMetadata() {
        Path tmpFolder = Files.createTempDirectory("bundle");
        String outputPath = tmpFolder.toAbsolutePath() + File.separator + "bundle.tar";
        HelperFunctions.containerToolCmd("pull", imageName);
        HelperFunctions.runCmd(CONTAINER_TOOL, "save", imageName, "-o=" + outputPath);

        try (TarArchiveInputStream inputStream = new TarArchiveInputStream(new FileInputStream(outputPath))) {
            unTar(inputStream, tmpFolder.toFile());
        }
        log.info("Unzipped archive: {}", tmpFolder.toAbsolutePath());

        File annotationsFile = new File(tmpFolder.toFile(), Paths.get("metadata", "annotations.yaml").toString());
        this.annotations = new Yaml().<Map<String, Map<String, String>>>load(new FileInputStream(annotationsFile)).get("annotations");

        File manifestFolder = new File(tmpFolder.toFile(), getManifestFolder());
        consumeManifestFolder(manifestFolder);
    }

    public void createSubscription() throws IOException {
        createSubscription(getPackageName(), getDefaultChannel(), getCSVName());
    }

    private static void createOperatorGroup(OpenShift ocp, String namespace) throws IOException {
        CustomResourceDefinitionContext operatorGroupCrdContext = new CustomResourceDefinitionContext.Builder()
            .withGroup("operators.coreos.com")
            .withPlural("operatorgroups")
            .withScope("Namespaced")
            .withVersion("v1alpha2")
            .build();

        String operatorGroupYaml = readResource("openshift/create-operatorgroup.yaml")
            .replaceAll("OPENSHIFT_PROJECT", namespace);

        JSONObject operatorGroupJsonObj = new JSONObject((Map)(new Yaml()).load(operatorGroupYaml));
        String operatorGroupJson = operatorGroupJsonObj.toString();
        GenericKubernetesResource k8resource = Serialization.jsonMapper().readValue(operatorGroupJson, GenericKubernetesResource.class);
        ocp.genericKubernetesResources(operatorGroupCrdContext).inNamespace(namespace).resource(k8resource).createOrReplace();
    }

    private static void createOperatorGroup(OpenShiftService service) throws IOException {
        OpenShift ocp = service.getClient();
        String namespace = ocp.getNamespace();

        createOperatorGroup(ocp, namespace);
    }

    public void createSubscription(String name, String channel, String startingCSV) throws IOException {
        ocpService.setupImageContentSourcePolicy();
        OpenShift ocp = ocpService.getClient();
        String namespace = ocpService.getClient().getNamespace();
        String subscription = HelperFunctions.readResource("openshift/create-subscriptionindex.yaml");
        subscription = subscription.replaceAll("NAMESPACE", namespace)
            .replaceAll("CHANNEL", channel)
            .replaceAll("STARTING_CSV", startingCSV == null ? "NOCSV" : startingCSV)
            .replaceAll("NAME", name)
            .replaceAll("SOURCE", index.getOcpName());

        if (startingCSV == null) {
            subscription = subscription.replaceAll("startingCSV\\: NOCSV", "");
        }
        if (ocp.getProject(namespace) == null) {
            ocp.createProjectRequest(namespace);
        }
        createOperatorGroup(ocpService);

        JSONObject subscriptionJsonObj = new JSONObject((Map)(new Yaml()).load(subscription));
        String subscriptionJson = subscriptionJsonObj.toString();
        GenericKubernetesResource k8resource = Serialization.jsonMapper().readValue(subscriptionJson, GenericKubernetesResource.class);
        ocp.genericKubernetesResources(subscriptionContext()).inNamespace(namespace).resource(k8resource).createOrReplace();
    }

    /**
     * Create a generic subscription CR
     *
     * @param service openshift service
     * @param name name of the subscription
     * @param channel channel to subscribe to
     * @param startingCSV starting CSV version
     * @param source the catalog source
     */
    @SneakyThrows
    public static void createSubscription(OpenShiftService service, String name, String channel, String startingCSV, String source) {
        service.setupImageContentSourcePolicy();
        OpenShift ocp = service.getClient();
        String namespace = service.getClient().getNamespace();
        String subscription = HelperFunctions.readResource("openshift/create-subscriptionindex.yaml");
        subscription = subscription.replaceAll("NAMESPACE", namespace)
            .replaceAll("CHANNEL", channel)
            .replaceAll("STARTING_CSV", startingCSV == null ? "NOCSV" : startingCSV)
            .replaceAll("NAME", name)
            .replaceAll("SOURCE", source);

        if (startingCSV == null) {
            subscription = subscription.replaceAll("startingCSV\\: NOCSV", "");
        }
        createOperatorGroup(service);

        JSONObject subscriptionJsonObj = new JSONObject((Map)(new Yaml()).load(subscription));
        String subscriptionJson = subscriptionJsonObj.toString();
        GenericKubernetesResource k8resource = Serialization.jsonMapper().readValue(subscriptionJson, GenericKubernetesResource.class);
        ocp.genericKubernetesResources(subscriptionContext()).inNamespace(namespace).resource(k8resource).createOrReplace();
    }

    /**
     * Start updating the bundle to newer version
     *
     * @param newBundle newer version of the bundle
     * @see Bundle#update(Bundle, boolean) for waiting for upgrade
     * @see Bundle#waitForUpdate(Bundle) for waiting for upgrade
     */
    @SneakyThrows
    public void update(Bundle newBundle) {
        OpenShift ocp = ocpService.getClient();
        String namespace = ocpService.getClient().getNamespace();

        JSONObject subscription = new JSONObject(ocp.genericKubernetesResources(subscriptionContext()).inNamespace(namespace).list().getItems().stream().filter(s -> s.getMetadata().getName().equals(subscriptionName)).findFirst());
        subscription.getJSONObject("spec").put("channel", newBundle.getDefaultChannel());
        waitFor(() -> {
            try {
                GenericKubernetesResource k8resource = Serialization.jsonMapper().readValue(subscription.toString(), GenericKubernetesResource.class);
                ocp.genericKubernetesResources(subscriptionContext()).inNamespace(namespace).resource(k8resource).createOrReplace();

                return true;
            } catch (Exception e) {
                return false;
            }
        }, 5000, 1000);
    }

    /**
     * Updated the currently installed bundle to a newer version.
     *
     * @param newBundle newer version of the bundle
     * @param wait should the call wait for the update to finish?
     */
    @SneakyThrows
    public void update(Bundle newBundle, boolean wait) {
        update(newBundle);

        if (wait) {
            waitForUpdate(newBundle);
        }
    }

    /**
     * Wait for the bundle to be updated to the new version.
     *
     * @param newBundle new version of the bundle
     */
    @SneakyThrows
    public void waitForUpdate(Bundle newBundle) {
        OpenShift ocp = ocpService.getClient();

        final String previousCSV = getCSVName();
        final String newCSV = newBundle.getCSVName();

        Filter completeFilter = Filter.filter(
            where("phase").is("Complete")
        );

        Filter matchesCSVs = Filter.filter(
            where("bundleLookups.identifier").eq(newCSV).and("bundleLookups.replaces").eq(previousCSV)
        );

        waitFor(() -> {
            final DocumentContext documentContext = JsonPath.parse(ocp.getClient().genericKubernetesResources(installPlanContext()).list().getItems().stream().filter(s -> s.getMetadata().getNamespace().equals(ocp.getNamespace())).findFirst());

            //Find all Complete installplans
            final Object read = documentContext
                .read("$.items[*].status[?]", completeFilter);

            //From the complete installplans find one that matches the CSVs
            final Object found = JsonPath.parse(read).read("$", matchesCSVs);

            return found != null;
        }, 2, 120 * 100);
    }

    public String getDefaultChannel() {
        String val = annotations.get("operators.operatorframework.io.bundle.channel.default.v1");
        return val == null ? getChannels()[0] : val;
    }

    /**
     * Assert images have the same id, see spec.relatedImages of CSV for proper names.
     *
     * @param images map of names and images to be checked against
     */
    public void assertSameImages(Map<String, String> images) {
        final JSONArray relatedImagesArray = getCsv().getJSONObject("spec").getJSONArray("relatedImages");
        Map<String, String> relatedImages = new HashMap<>();
        for (int i = 0; i < relatedImagesArray.length(); i++) {
            final JSONObject image = relatedImagesArray.getJSONObject(i);
            relatedImages.put(image.getString("name"), image.getString("image"));
        }

        SoftAssertions sa = new SoftAssertions();

        DockerClientConfig standard = DefaultDockerClientConfig
            .createDefaultConfigBuilder()
            .build();

        DockerHttpClient httpClient = new OkDockerHttpClient.Builder()
            .dockerHost(standard.getDockerHost())
            .sslConfig(standard.getSSLConfig())
            .connectTimeout(30)
            .readTimeout(45)
            .build();

        DockerClient dockerClient = DockerClientImpl.getInstance(standard, httpClient);

        Assertions.assertThatCode(() -> dockerClient.pingCmd().exec()).describedAs("Cannot connect to docker to check images IDs")
            .doesNotThrowAnyException();

        images.forEach((name, image1) -> {
            String image2 = relatedImages.get(name);

            if (image2 == null) {
                sa.fail("Image " + name + " was not found in the CSV, known names are: " + relatedImages.keySet());
                return;
            }

            if (ocpService.getOpenShiftConfiguration().getDockerRegistry() != null) {
                image2 = image2.replaceFirst("[^/]+/", ocpService.getOpenShiftConfiguration().getDockerRegistry() + "/");
            }

            try {
                HelperFunctions.containerToolCmd("pull", image1);
                HelperFunctions.containerToolCmd("pull", image2);
            } catch (Exception e) {
                sa.fail("Couldn't pull image", e);
                return;
            }

            sa.assertThat(dockerClient.inspectImageCmd(image1).exec().getId()).isEqualTo(dockerClient.inspectImageCmd(image2).exec().getId())
                .as("Expected image with name '%s' images %s and %s to have the same ids", name, image1, image2);
        });
        sa.assertAll();
    }

    public String[] getChannels() {
        return annotations.get("operators.operatorframework.io.bundle.channels.v1").split(",");
    }

    public String getMediaType() {
        return annotations.get("operators.operatorframework.io.bundle.mediatype.v1");
    }

    public String getPackageName() {
        return annotations.get("operators.operatorframework.io.bundle.package.v1");
    }

    private String getManifestFolder() {
        return annotations.get("operators.operatorframework.io.bundle.manifests.v1");
    }

    private String getMetadataFolder() {
        return annotations.get("operators.operatorframework.io.bundle.metadata.v1");
    }

    public String getCSVName() {
        return csv.getJSONObject("metadata").getString("name");
    }
}
