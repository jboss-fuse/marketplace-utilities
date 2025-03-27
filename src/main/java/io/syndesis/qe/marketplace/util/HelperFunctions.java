package io.syndesis.qe.marketplace.util;

import org.apache.commons.codec.binary.Base64;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.apache.http.HttpEntityEnclosingRequest;
import org.apache.http.HttpResponse;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpDelete;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.HttpClients;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLParser;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import cz.xtf.core.openshift.OpenShift;
import io.fabric8.kubernetes.api.model.LocalObjectReference;
import io.fabric8.kubernetes.api.model.ServiceAccount;
import io.fabric8.kubernetes.api.model.ServiceAccountList;
import io.fabric8.kubernetes.api.model.apiextensions.v1.CustomResourceDefinition;
import io.fabric8.kubernetes.client.dsl.base.CustomResourceDefinitionContext;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class HelperFunctions {
    public static String getPackageName(Path targetPath) throws IOException {
        try (Stream<Path> walk = Files.walk(targetPath)) {
            List<String> yamlFiles = walk.map(Path::toString)
                .filter(x -> x.endsWith(".package.yaml"))
                .collect(Collectors.toList());

            if (yamlFiles.size() > 0) {
                YAMLParser parser = new YAMLFactory().createParser(new File(yamlFiles.get(0)));
                ObjectMapper mapper = new ObjectMapper();
                JsonNode packageFile = mapper.readTree(parser);

                return packageFile.get("packageName").asText();
            }
        }

        return "";
    }

    public static String getOperatorVersion(Path targetPath) throws IOException {
        try (Stream<Path> walk = Files.walk(targetPath)) {
            List<String> directories = walk.filter(Files::isDirectory)
                .map(Path::toString)
                .sorted(Comparator.comparing(String::toString))
                .collect(Collectors.toList());

            if (directories.size() > 0) {
                String lastDir = directories.get(directories.size() - 1);
                String[] parts = lastDir.split("/");
                return parts[parts.length - 1];
            }
        }

        return "";
    }

    public static String getOperatorName(String operatorImage) {
        String[] parts = operatorImage.split("/");
        String[] smallerParts = parts[parts.length - 1].split(":");
        return smallerParts[0];
    }

    public static String encodeFileToBase64Binary(String fileName) throws IOException {
        File file = new File(fileName);
        byte[] encoded = Base64.encodeBase64(FileUtils.readFileToByteArray(file));
        return new String(encoded, StandardCharsets.US_ASCII);
    }

    public static String readResource(String resourceName) throws IOException {
        StringBuffer sb = new StringBuffer("");
        InputStream is = Thread.currentThread().getContextClassLoader().getResourceAsStream(resourceName);
        try (InputStreamReader reader = new InputStreamReader(is)) {
            BufferedReader bf = new BufferedReader(reader);

            String line;
            while ((line = bf.readLine()) != null) {
                sb.append(line).append("\n");
            }
        }

        return sb.toString();
    }

    public static String doPostRequest(String url, String body, String auth) throws IOException {
        HttpPost httpPost = new HttpPost(url);

        return executeRequest(httpPost, body, auth);
    }

    public static String doDeleteRequest(String url, String body, String auth) throws IOException {
        HttpDelete httpDelete = new HttpDelete(url);

        return executeRequest(httpDelete, body, auth);
    }

    private static String executeRequest(HttpUriRequest request, String body, String auth) throws IOException {
        HttpClient client = HttpClients.createDefault();

        // if we need to use body and if request supports payload then we can attach it
        if (body != null && request instanceof HttpEntityEnclosingRequest) {
            ((HttpEntityEnclosingRequest) request).setEntity(new StringEntity(body));
        }
        request.addHeader("Content-Type", "application/json; utf-8");
        request.addHeader("Accept", "application/json");
        if (auth != null) {
            request.addHeader("Authorization", auth);
        }

        HttpResponse httpResponse = client.execute(request);
        StringBuilder sb = new StringBuilder();
        if (httpResponse != null && httpResponse.getEntity() != null) {
            try (BufferedReader br = new BufferedReader(
                new InputStreamReader(httpResponse.getEntity().getContent(), StandardCharsets.UTF_8))) {
                String responseLine;
                while ((responseLine = br.readLine()) != null) {
                    sb.append(responseLine.trim());
                }
            }
        }

        return sb.toString();
    }

    public static void linkPullSecret(OpenShift openshiftClient, String openshiftNamespace, String operatorName, String pullSecret) {
        ServiceAccountList saList = openshiftClient.inNamespace(openshiftNamespace)
            .serviceAccounts().list();

        Optional<ServiceAccount> serviceAccount = saList.getItems().stream()
            .filter(sa -> sa.getMetadata().getName().equals(operatorName))
            .findFirst();

        if (serviceAccount.isPresent()) {
            ServiceAccount sa = serviceAccount.get();
            sa.getImagePullSecrets().add(new LocalObjectReference(pullSecret));
            openshiftClient.serviceAccounts().inNamespace(openshiftNamespace)
                .createOrReplace(sa);
        } else {
            log.error("Service account not found in resources");
        }
    }

    public static String copyManifestFilestFromImage(String image, String targetDirectory) throws IOException {
        if (checkSystemIsMac()) {
            targetDirectory = "/private" + targetDirectory;
        }

        ProcessBuilder builder = new ProcessBuilder(
            "docker", "run",
            "-v", targetDirectory + ":/opt/mount",
            "--user",
            "1000:1000",
            "--rm",
            "--entrypoint", "cp",
            image,
            "-r", "/manifests", "/opt/mount/"
        );
        builder.redirectErrorStream(false);
        Process p = builder.start();

        BufferedReader r = new BufferedReader(new InputStreamReader(p.getErrorStream()));

        String line;
        StringBuilder sb = new StringBuilder();
        while ((line = r.readLine()) != null) {
            sb.append(line).append("\n");
        }

        return sb.toString();
    }

    private static boolean checkSystemIsMac() {
        String osName = System.getProperty("os.name").toLowerCase();
        return osName.contains("mac");
    }

    public static void replaceImageInManifest(Path file, String operatorImage, Map<String, String> envVars) throws IOException {
        YAMLParser parser = new YAMLFactory().createParser(file.toFile());
        ObjectMapper mapper = new ObjectMapper();
        JsonNode manifestFile = mapper.readTree(parser);

        JsonNode containerObject = manifestFile.get("spec").get("install").get("spec")
            .get("deployments").get(0).get("spec").get("template").get("spec")
            .get("containers").get(0);

        ((ObjectNode) containerObject).put("image", operatorImage);

        if (envVars != null && !envVars.isEmpty()) {
            ArrayNode envArray = (ArrayNode) containerObject.get("env");
            for (Map.Entry<String, String> entry : envVars.entrySet()) {
                ObjectNode newNode = mapper.createObjectNode();
                newNode.put("name", entry.getKey());
                newNode.put("value", entry.getValue());
                envArray.add(newNode);
            }
        }

        String yaml = new YAMLMapper().writeValueAsString(manifestFile);

        BufferedWriter writer = new BufferedWriter(new FileWriter(file.toString()));
        writer.write(yaml);
        writer.close();
    }

    public static boolean waitFor(BooleanSupplier condition, long interval, long timeout)
        throws InterruptedException, TimeoutException {

        long intervalInMilis = interval * 1000;
        long timeoutInMilis = timeout * 1000;

        long waitUntil = System.currentTimeMillis() + timeoutInMilis;

        while (System.currentTimeMillis() < waitUntil) {
            if (condition.getAsBoolean()) {
                return true;
            }

            Thread.sleep(intervalInMilis);
        }

        throw new TimeoutException();
    }

    public static CustomResourceDefinitionContext getContext(CustomResourceDefinition crd) {
        CustomResourceDefinitionContext.Builder builder = new CustomResourceDefinitionContext.Builder()
            .withGroup(crd.getSpec().getGroup())
            .withPlural(crd.getSpec().getNames().getPlural())
            .withScope(crd.getSpec().getScope())
            .withVersion(crd.getSpec().getVersions().get(0).toString());
        return builder.build();
    }

    public static void runCmd(String... command) {
        try {
            Process process = new ProcessBuilder(command).start();
            int ret = 0;
            ret = process.waitFor();
            if (ret != 0) {
                String out = IOUtils.toString(process.getErrorStream(), Charset.defaultCharset()) + "\n" +
                    IOUtils.toString(process.getInputStream(), Charset.defaultCharset());
                throw new RuntimeException(
                    String.format("Command finished with non-zero value!, Command: '%s', Output: '%s'", Arrays.toString(command), out));
            }
        } catch (InterruptedException | IOException e) {
            new RuntimeException(e);
        }
    }
}
