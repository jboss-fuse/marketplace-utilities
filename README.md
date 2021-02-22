Marketplace Utilities
=====================

## What is it for

This library is intended for automation of operator deployment
on OpenShift 4 via OpenShift Marketplace.

## How to use

Example of usage below:

### Quay

First it's necessary to create project on quay:
```
QuayUser quayUser = new QuayUser(
        userName,
        password,
        organization, // namespace
        authToken
);

QuayService quayService = new QuayService(
    quayUser,
    operatorImage,
    envVars);

quayProject = quayService.createQuayProject();
```

* `operatorImage` is an image we want to install
* `envVars` is a map of environment variables we want to set
  for this image

This method returns name of project on Quay (it's based on operator name).
It's necessary in the next steps.

### OpenShift

When project on Quay is created, we can get to deploying on OpenShift
```
OpenShiftUser regularUser = new OpenShiftUser(
        regularUserUsername,
        regularUserPassword,
        openShiftApiUrl
);
OpenShiftUser adminUser = new OpenShiftUser(
        adminUsername,
        adminPassword,
        openShiftApiUrl
);
OpenShiftConfiguration openShiftConfiguration = new OpenShiftConfiguration(
        openShiftNamespace,
        pullSecretName,
        pullSecret,
        quayOperatorsourceToken
);
OpenShiftService openShiftService = new OpenShiftService(
        quayNamespace,
        quayProject, // this is from before
        openShiftConfiguration,
        adminUser,
        defaultUser
);

openShiftService.deployOperator();
```

Regular user object can be null in case it is not needed. Pull secret can be null in case not needed either.

#### Opsrc token

The opsrc token needs to be used if quay starts to return 404 pages. It can be obtained by creating a robot account 
in your namespace (also set the default permissions of the robot). Then to obtain the token encode your <robot-name>:<robot-token>
in Base64. This Base64 result needs to be encoded again with the word 'basic' prefixing it. 

In example: 

* `fuseorg+robot:token123` -> `ZnVzZW9yZytyb2JvdDp0b2tlbjEyMw==` 

* `basic ZnVzZW9yZytyb2JvdDp0b2tlbjEyMw==` -> `YmFzaWMgWm5WelpXOXlaeXR5YjJKdmREcDBiMnRsYmpFeU13PT0=`

* **YmFzaWMgWm5WelpXOXlaeXR5YjJKdmREcDBiMnRsYmpFeU13PT0=** is the opsrc token

### Cleanup

After everything done it's recommended to clean Operatorsource and it's token:

```
openShiftService.deleteOpsrcToken();
openShiftService.deleteOperatorSource();
quayService.deleteQuayProject();
```

Both services are stateful, so it's necessary to use same objects for cleanup.


