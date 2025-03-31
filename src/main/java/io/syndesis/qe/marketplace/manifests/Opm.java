package io.syndesis.qe.marketplace.manifests;

import io.syndesis.qe.marketplace.openshift.OpenShiftService;
import io.syndesis.qe.marketplace.openshift.OpenShiftUser;
import io.syndesis.qe.marketplace.quay.QuayUser;
import io.syndesis.qe.marketplace.util.HelperFunctions;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;

import cz.xtf.core.openshift.OpenShifts;
import io.fabric8.kubernetes.client.dsl.base.CustomResourceDefinitionContext;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class Opm {

    private File binary;
    private static final String OPM_IMAGE = "registry.redhat.io/openshift4/ose-operator-registry:";
    private static final String OPM_BINARY_URL = "https://mirror.openshift.com/pub/openshift-v4/x86_64/clients/ocp/stable/opm-linux.tar.gz";
    private final OpenShiftService ocpSvc;

    public Opm(OpenShiftService ocpSvc) {
        this.ocpSvc = ocpSvc;
        try {
            Process which = new ProcessBuilder("which", "opm").start();
            int result = which.waitFor();
            String path = IOUtils.toString(which.getInputStream(), Charset.defaultCharset()).trim();
            if (result != 0) {
                fetchOpm();
            } else {
                binary = new File(path);
            }
        } catch (InterruptedException | IOException e) {
            throw new RuntimeException(e);
        }
    }

    //Get version of the OCP server to a suitable image tag (4.16.7 -> v4.16)
    private String getTag() {
        CustomResourceDefinitionContext crdContext = new CustomResourceDefinitionContext.Builder()
            .withGroup("config.openshift.io")
            .withVersion("v1")
            .withKind("ClusterOperator")
            .withPlural("clusteroperators")
            .withScope("Cluster")
            .build();

        JSONObject apiServer = new JSONObject(this.ocpSvc.getClient().genericKubernetesResources(crdContext).list().getItems().stream().filter(s -> s.getMetadata().getName().equals("openshift-apiserver")).findFirst());
        String fullVersion = apiServer.getJSONObject("status").getJSONArray("versions").getJSONObject(0).getString("version");
        return "v" + fullVersion.substring(0, fullVersion.lastIndexOf("."));
    }

    private void fetchOpm() {
        try {
            final File binaryFolder = new File("/tmp/marketplace-utilities-opm/");
            if (binaryFolder.exists()) {
                if (new File(binaryFolder, "opm").exists()) {
                    binary = new File(binaryFolder, "opm");
                    return;
                }
            } else {
                Files.createDirectory(binaryFolder.toPath());
            }
            Path binaryPath = binaryFolder.toPath();
            OpenShiftUser user = ocpSvc.getAdminUser();
            HelperFunctions.runCmd(OpenShifts.getBinaryPath(), "login", "--insecure-skip-tls-verify", "-u", user.getUserName(), "-p", user.getPassword(), user.getApiUrl());
            try {
                HelperFunctions
                    .runCmd(OpenShifts.getBinaryPath(), "image", "extract", OPM_IMAGE + getTag(), "--path", "/usr/bin/registry/opm:" + binaryPath.toAbsolutePath());
            } catch (RuntimeException ignored) {
                final Path opmArchiveFile = Files.createTempFile("opm-binary", ".tar.gz");
                FileUtils.copyURLToFile(new URL(OPM_BINARY_URL), opmArchiveFile.toFile());
                HelperFunctions.runCmd("tar", "xzf", opmArchiveFile.toAbsolutePath().toString(), "-C", binaryPath.toAbsolutePath().toString());
            }
            binary = binaryPath.resolve("opm").toFile();
            binary.setExecutable(true);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @SneakyThrows
    void runOpmCmd(String... args) {
        String[] command = new String[args.length + 1];
        System.arraycopy(args, 0, command, 1, args.length);
        command[0] = binary.getAbsolutePath();
        HelperFunctions.runCmd(command);
    }

    public Index createIndex(String name, QuayUser quayUser) {
        return new Index(name, ocpSvc, this, quayUser);
    }

    public Index pullIndex(String name, QuayUser user) {
        Index index = new Index(name, ocpSvc, this, user);
        index.pull();
        return index;
    }
}
