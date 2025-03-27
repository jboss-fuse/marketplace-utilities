package io.syndesis.qe.marketplace.manifests;

import static io.syndesis.qe.marketplace.util.HelperFunctions.readResource;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.io.IOUtils;

import org.yaml.snakeyaml.Yaml;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import cz.xtf.core.openshift.OpenShift;
import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.client.dsl.base.CustomResourceDefinitionContext;
import io.fabric8.kubernetes.client.utils.Serialization;
import io.syndesis.qe.marketplace.openshift.OpenShiftService;
import io.syndesis.qe.marketplace.util.HelperFunctions;
import lombok.Getter;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class Bundle {
    @Getter
    private final String imageName;
    private static final String CONTAINER_TOOL = "docker";
    @Getter
    private Map<String, String> annotations;
    @Getter
    private String csv;
    @Getter
    private List<String> crds;
    private Index index;

    Bundle(String imageName, Index index) {
        this.imageName = imageName;
        this.index = index;
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

    private static void unTar(TarArchiveInputStream tis, File destFolder) throws IOException {
        TarArchiveEntry tarEntry = null;
        while ((tarEntry = tis.getNextTarEntry()) != null) {
            if (tarEntry.isDirectory()) {
                new File(destFolder, tarEntry.getName()).mkdirs();
            } else {
                File result = new File(destFolder, tarEntry.getName());
                FileOutputStream fos = new FileOutputStream(result);
                IOUtils.copy(tis, fos);
                if (result.getName().equals("layer.tar")) {
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
            csv = readFile(Paths.get(manifestFolder.getAbsolutePath(), csvPath.get()).toString());
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
        HelperFunctions.runCmd(CONTAINER_TOOL, "pull", imageName);
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

    public void createSubscription(OpenShiftService service) throws IOException {
        createSubscription(service, getPackageName(), getDefaultChannel(), getCSVName());
    }

    private void createOperatorGroup(OpenShiftService service) throws IOException {
        OpenShift ocp = service.getClient();
        String namespace = service.getClient().getNamespace();

        CustomResourceDefinitionContext operatorGroupCrdContext = new CustomResourceDefinitionContext.Builder()
            .withGroup("operators.coreos.com")
            .withPlural("operatorgroups")
            .withScope("Namespaced")
            .withVersion("v1alpha2")
            .build();

        String operatorGroupYaml = readResource("openshift/create-operatorgroup.yaml")
            .replaceAll("OPENSHIFT_PROJECT", namespace);

        GenericKubernetesResource k8resource = Serialization.jsonMapper().readValue(operatorGroupYaml, GenericKubernetesResource.class);
        ocp.genericKubernetesResources(operatorGroupCrdContext).inNamespace(namespace).resource(k8resource).createOrReplace();

    }

    public void createSubscription(OpenShiftService service, String name, String channel, String startingCSV) throws IOException {
        service.setupImageContentSourcePolicy();
        OpenShift ocp = service.getClient();
        String namespace = service.getClient().getNamespace();
        String subscription = HelperFunctions.readResource("openshift/create-subscriptionindex.yaml");
        subscription = subscription.replaceAll("NAMESPACE", namespace)
            .replaceAll("CHANNEL", channel)
            .replaceAll("STARTING_CSV", startingCSV)
            .replaceAll("NAME", name)
            .replaceAll("SOURCE", index.getOcpName());

        if (ocp.getProject(namespace) == null) {
            ocp.createProjectRequest(namespace);
        }
        createOperatorGroup(service);

        GenericKubernetesResource k8resource = Serialization.jsonMapper().readValue(subscription, GenericKubernetesResource.class);
        ocp.genericKubernetesResources(subscriptionContext()).inNamespace(namespace).resource(k8resource).createOrReplace();
    }

    @SneakyThrows
    public static void createSubscription(OpenShiftService service, String name, String channel, String startingCSV, String source) {
        OpenShift ocp = service.getClient();
        String namespace = service.getClient().getNamespace();
        String subscription = HelperFunctions.readResource("openshift/create-subscriptionindex.yaml");
        subscription = subscription.replaceAll("NAMESPACE", namespace)
            .replaceAll("CHANNEL", channel)
            .replaceAll("STARTING_CSV", startingCSV)
            .replaceAll("NAME", name)
            .replaceAll("SOURCE", source);

        GenericKubernetesResource k8resource = Serialization.jsonMapper().readValue(subscription, GenericKubernetesResource.class);
        ocp.genericKubernetesResources(subscriptionContext()).inNamespace(namespace).resource(k8resource).createOrReplace();
    }

    public String getDefaultChannel() {
        String val = annotations.get("operators.operatorframework.io.bundle.channel.default.v1");
        return val == null ? getChannels()[0] : val;
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
        return ((String) new Yaml().<Map<String, Map<String, Object>>>load(getCsv()).get("metadata").get("name"));
    }
}
