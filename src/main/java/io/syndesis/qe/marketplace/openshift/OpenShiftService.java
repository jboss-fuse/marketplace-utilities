package io.syndesis.qe.marketplace.openshift;

import static io.syndesis.qe.marketplace.util.HelperFunctions.readResource;
import static io.syndesis.qe.marketplace.util.HelperFunctions.runCmd;
import static io.syndesis.qe.marketplace.util.HelperFunctions.waitFor;

import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.api.model.SecretBuilder;
import io.fabric8.kubernetes.api.model.ServiceAccount;
import io.fabric8.kubernetes.api.model.ServiceAccountBuilder;
import io.fabric8.kubernetes.client.utils.Serialization;
import io.syndesis.qe.marketplace.util.HelperFunctions;

import org.apache.commons.codec.binary.StringUtils;
import org.apache.commons.io.FileUtils;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeoutException;

import cz.xtf.core.openshift.OpenShift;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.api.model.SecretList;
import io.fabric8.kubernetes.api.model.apps.DeploymentList;
import io.fabric8.kubernetes.client.KubernetesClientException;
import io.fabric8.kubernetes.client.dsl.base.CustomResourceDefinitionContext;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class OpenShiftService {

    private final String quayNamespace;
    private final String quayPackageName;

    private final OpenShift openShiftClient;

    private final OpenShiftConfiguration openShiftConfiguration;
    private final OpenShiftUser adminUser;

    private OpenShift openShiftClientAsRegularUser;

    public OpenShiftService(
        String quayNamespace,
        String quayPackageName,
        OpenShiftConfiguration openShiftConfiguration,
        OpenShiftUser adminOpenShiftUser,
        OpenShiftUser regularOpenShiftUser) {

        this.quayNamespace = quayNamespace;
        this.quayPackageName = quayPackageName;
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

    public void deployOperator() throws IOException {
        createNamespace();
        createPullSecret();
        disableDefaultSources();
        createOpsrcToken();
        createOpsrc();
        createOperatorgroup();
        createSubscription();

        DeploymentList deploymentList =
            openShiftClient.apps().deployments().inNamespace(openShiftConfiguration.getNamespace()).list();
        if (deploymentList.getItems().size() != 1) {
            log.error("Must be one deployment, actual number is " + deploymentList.getItems().size());
            throw new IOException("There must be one deployment");
        }

        String operatorResourcesName = deploymentList.getItems().get(0).getMetadata().getName();

        log.info("Operator pod name is '" + operatorResourcesName + "'");

        linkPullSecret(operatorResourcesName);

        log.info("Redeploying operator pod so it uses new pull secret");

        scaleOperatorPod(0, operatorResourcesName);
        scaleOperatorPod(1, operatorResourcesName);
    }

    public void deleteOpsrcToken() {
        log.info("Deleting opsrc token");
        SecretList secretList = openShiftClient.secrets().inNamespace("openshift-marketplace").list();
        for (Secret secret : secretList.getItems()) {
            if (StringUtils.equals(secret.getMetadata().getName(), quayPackageName + "-opsrctoken")) {
                openShiftClient.secrets().inNamespace("openshift-marketplace").delete(secret);
            }
        }
    }

    public void deleteOperatorSource() throws IOException {
        log.info("Deleting operator source for quay package '" + quayPackageName + "'");
        CustomResourceDefinitionContext operatorSourceCrdContext = new CustomResourceDefinitionContext.Builder()
            .withGroup("operators.coreos.com")
            .withPlural("operatorsources")
            .withScope("Namespaced")
            .withVersion("v1")
            .build();

        openShiftClient.genericKubernetesResources(operatorSourceCrdContext).inNamespace("openshift-marketplace").withName(quayPackageName + "-opsrc").delete();
    }

    public void refreshOperators() {
        openShiftClient.pods().inNamespace("openshift-marketplace")
            .delete();
    }

    @SneakyThrows
    public void setupImageContentSourcePolicy() {
        CustomResourceDefinitionContext operatorSourceCrdContext = new CustomResourceDefinitionContext.Builder()
            .withGroup("operator.openshift.io")
            .withPlural("imagecontentsourcepolicies")
            .withScope("Cluster")
            .withVersion("v1alpha1")
            .build();

        try {
            openShiftClient.genericKubernetesResources(operatorSourceCrdContext).withName("brew-registry").get();
        } catch (KubernetesClientException e) {
            log.info("ICSP was not found, creating new!");
            if (openShiftConfiguration.getIcspConfigURL() == null) {
                throw new RuntimeException("ICSP is configured by a script. Set this script URL in OpenshiftConfiguration please.");
            }
            File script = new File("/tmp/brew-registry-script.sh");
            FileUtils.copyURLToFile(new URL(openShiftConfiguration.getIcspConfigURL()), script);
            script.setExecutable(true);
            runCmd("oc", "login", "-u", adminUser.getUserName(), "-p", adminUser.getPassword(), adminUser.getApiUrl());
            runCmd(script.getAbsolutePath());
        }
    }

    /**
     * Configures openshift-marketplace to pull from Quay and mirror internal registries
     *
     * @param pullSecretContent - base64 encoded Docker auths json
     */
    public void patchGlobalSecrets(String pullSecretContent) {
        Map<String, String> obligatoryMap = new HashMap<>();
        obligatoryMap.put(".dockerconfigjson", pullSecretContent);

        Secret s = new SecretBuilder()
                .withStringData(obligatoryMap)
                .withType("kubernetes.io/dockerconfigjson")
                .withNewMetadata()
                    .withName("quay-pull-secret")
                    .withNamespace("openshift-marketplace")
                .endMetadata()
                .build();
        openShiftClient.secrets().createOrReplace(s);

        ServiceAccount sa = new ServiceAccountBuilder()
            .withNewMetadata()
                .withName("default")
            .endMetadata()
            .editFirstSecret()
                .withName(s.getMetadata().getName())
                .withNamespace(s.getMetadata().getNamespace())
            .endSecret()
        .build();
        openShiftClient.serviceAccounts().inNamespace("openshift-marketplace").resource(sa)
                .serverSideApply();

    }

    private void disableDefaultSources() throws IOException {
        log.info("Disabling default sources on openshift");

        CustomResourceDefinitionContext crdContext = new CustomResourceDefinitionContext.Builder()
            .withGroup("config.openshift.io")
            .withPlural("operatorhubs")
            .withScope("Cluster")
            .withVersion("v1")
            .build();

        String dfs = HelperFunctions.readResource("openshift/disable-default-sources.yaml");

        GenericKubernetesResource k8resource = Serialization.jsonMapper().readValue(dfs, GenericKubernetesResource.class);
        openShiftClient.genericKubernetesResources(crdContext).inNamespace("openshift-marketplace").resource(k8resource).createOrReplace();
    }

    private void createOpsrcToken() throws IOException {
        log.info("Creating operatorsource secret token");
        Map<String, String> data = new HashMap<>();
        data.put("token", openShiftConfiguration.getQuayOpsrcToken());

        Secret s = new SecretBuilder()
                .withStringData(data)
                .withType("Opaque")
                .withNewMetadata()
                .withName(quayPackageName + "-opsrctoken")
                .withNamespace("openshift-marketplace")
                .endMetadata()
                .build();
        openShiftClient.secrets().createOrReplace(s);

    }

    private void createOpsrc() throws IOException {
        log.info("Creating operator source which points toward quay");

        CustomResourceDefinitionContext operatorSourceCrdContext = new CustomResourceDefinitionContext.Builder()
            .withGroup("operators.coreos.com")
            .withPlural("operatorsources")
            .withScope("Namespaced")
            .withVersion("v1")
            .build();

        String operatorSourceYaml = readResource("openshift/create-operatorsource.yaml")
            .replaceAll("PACKAGE_NAME", quayPackageName)
            .replaceAll("QUAY_NAMESPACE", quayNamespace);

        GenericKubernetesResource k8resource = Serialization.jsonMapper().readValue(operatorSourceYaml, GenericKubernetesResource.class);
        openShiftClient.genericKubernetesResources(operatorSourceCrdContext).inNamespace("openshift-marketplace").resource(k8resource).createOrReplace();
    }

    private void createNamespace() throws IOException {
        if (openShiftClient.getProject(openShiftConfiguration.getNamespace()) != null) {
            log.info("Namespace exists, deleting namespace first");
            openShiftClient.deleteProject(openShiftConfiguration.getNamespace());
            try {
                HelperFunctions.waitFor(
                    () -> openShiftClient.getProject(openShiftConfiguration.getNamespace()) == null,
                    1, 30);
            } catch (InterruptedException | TimeoutException e) {
                log.error("Namespace was not deleted");
                throw new IOException("Namespace was not created", e);
            }
        }

        log.info("Creating namespace");

        if (openShiftClientAsRegularUser != null) {
            openShiftClientAsRegularUser.createProjectRequest(openShiftConfiguration.getNamespace());
        } else {
            openShiftClient.createProjectRequest(openShiftConfiguration.getNamespace());
        }

        try {
            HelperFunctions.waitFor(
                () -> openShiftClient.getProject(openShiftConfiguration.getNamespace()) != null,
                1, 30);
        } catch (InterruptedException | TimeoutException e) {
            log.error("Namespace was not created");
            throw new IOException("Namespace was not created", e);
        }
    }

    private void createPullSecret() throws IOException {
        log.info("Creating pull secret");

        if (openShiftConfiguration.getPullSecret() != null) {
            log.info("Creating a pull secret with name " + openShiftConfiguration.getPullSecretName());
            Map<String, String> pullSecretMap = new HashMap<>();
            pullSecretMap.put(".dockerconfigjson", openShiftConfiguration.getPullSecret());

            Secret s = new SecretBuilder()
                    .withStringData(pullSecretMap)
                    .withType("kubernetes.io/dockerconfigjson")
                    .withNewMetadata()
                    .withName(openShiftConfiguration.getPullSecretName())
                    .endMetadata()
                    .build();
            openShiftClient.secrets().createOrReplace(s);
        }
    }

    private void createOperatorgroup() throws IOException {
        log.info("Creating operatorgroup");

        CustomResourceDefinitionContext operatorGroupCrdContext = new CustomResourceDefinitionContext.Builder()
            .withGroup("operators.coreos.com")
            .withPlural("operatorgroups")
            .withScope("Namespaced")
            .withVersion("v1alpha2")
            .build();

        String operatorGroupYaml = readResource("openshift/create-operatorgroup.yaml")
            .replaceAll("OPENSHIFT_PROJECT", openShiftConfiguration.getNamespace());

        GenericKubernetesResource k8resource = Serialization.jsonMapper().readValue(operatorGroupYaml, GenericKubernetesResource.class);
        openShiftClient.genericKubernetesResources(operatorGroupCrdContext).inNamespace(openShiftConfiguration.getNamespace()).resource(k8resource).createOrReplace();
    }

    private void createSubscription() throws IOException {
        setupImageContentSourcePolicy();

        log.info("Creating operator subscription");

        String subscriptionYaml = readResource("openshift/create-subscription.yaml")
            .replaceAll("PACKAGE_NAME", quayPackageName)
            .replaceAll("OPENSHIFT_PROJECT", openShiftConfiguration.getNamespace());

        if (openShiftConfiguration.getInstalledCSV() != null) {
            subscriptionYaml = subscriptionYaml.replaceAll("STARTING_CSV", openShiftConfiguration.getInstalledCSV());
        } else {
            subscriptionYaml = subscriptionYaml.replaceAll("\\s*\\w*:\\s*STARTING_CSV", "");
        }

        CustomResourceDefinitionContext subscriptionCrdContext = new CustomResourceDefinitionContext.Builder()
            .withGroup("operators.coreos.com")
            .withPlural("subscriptions")
            .withScope("Namespaced")
            .withVersion("v1alpha1")
            .build();

        GenericKubernetesResource k8resource = Serialization.jsonMapper().readValue(subscriptionYaml, GenericKubernetesResource.class);
        openShiftClient.genericKubernetesResources(subscriptionCrdContext).inNamespace(openShiftConfiguration.getNamespace()).resource(k8resource).createOrReplace();

        try {
            waitFor(() ->
                    openShiftClient.inNamespace(openShiftConfiguration.getNamespace()).pods().list().getItems().size() == 1,
                1, 2 * 60);
        } catch (InterruptedException | TimeoutException e) {
            log.error("There is no pod in project after waiting for 120 seconds");
            throw new IOException("Pod has not been created and/or stared", e);
        }
    }

    private void scaleOperatorPod(int scale, String operatorResourcesName) throws IOException {
        openShiftClient
            .apps().deployments().inNamespace(openShiftConfiguration.getNamespace())
            .withName(operatorResourcesName).scale(scale);
        try {
            HelperFunctions.waitFor(
                () -> openShiftClient.pods().inNamespace(openShiftConfiguration.getNamespace())
                    .list().getItems().size() == scale,
                1, 30
            );
        } catch (InterruptedException | TimeoutException e) {
            log.error("Couldn't wait for pod to scale");
            throw new IOException("Operator pod did not scale", e);
        }
    }

    private void linkPullSecret(String operatorResourcesName) {
        log.info("Linking pull secret to service account user");

        HelperFunctions.linkPullSecret(
            openShiftClient,
            openShiftConfiguration.getNamespace(),
            operatorResourcesName,
            openShiftConfiguration.getPullSecretName());
    }

    public OpenShift getClient() {
        return openShiftClient;
    }

    public OpenShiftUser getAdminUser() {
        return adminUser;
    }
}
